(ns artemis.document
  #?(:cljs (:require-macros artemis.document))
  (:require [clojure.spec.alpha :as s]
            [graphql-builder.core :as gql]
            [camel-snake-kebab.core :refer [->kebab-case]]
            #?@(:clj [[graphql-clj.parser :as parser]
                      [graphql-clj.box :as box]
                      [instaparse.core :as insta]
                      [clojure.java.io :as io]
                      [clojure.string :as string]
                      [clojure.walk :as w]])))

(defn- ^boolean single-operation?
  "True if document contains more than one operation."
  [document]
  (= 1 (count (:operation-definitions document))))

(s/def ::document (s/keys :opt-un [::operation-definitions ::fragment-definitions]))
(s/def ::operation (s/keys :req-un [::graphql ::unpack]))

(s/fdef operation
        :args (s/alt
                :arity-1 (s/cat :document ::document)
                :arity-2 (s/cat :document  ::document
                                :variables (s/nilable map?))
                :arity-3 (s/cat :document          ::document
                                :variables         (s/nilable map?)
                                :inline-fragments? boolean?))
        :ret  ::operation)

(defn operation
  "Given a document and some variables, return a map that describes the
  operation to execute under the :graphql key and a function to neatly unpack
  the data that results from that operation under the :unpack key. Unpacking
  reifies operations that were namespaced as a result of applying an operation
  mapping."
  {:added "0.1.0"}
  ([document]
   (operation document {} false))
  ([document variables]
   (operation document variables false))
  ([document variables inline-fragments?]
   (if (single-operation? document)
     (let [op    (-> document :operation-definitions first :operation-type)
           ops   (get (gql/query-map document {:inline-fragments inline-fragments?})
                      (keyword (:type op)))
           op-fn (get ops (some-> (:name op) ->kebab-case keyword)
                          ;; If unnamed operation, we'll just grab it out by
                          ;; position 1, which is always going to be the case
                          (-> ops first second))]
       (op-fn variables))
     (let [op-map (::operation-mapping (meta document) {})]
       ((gql/composed-query document op-map)
        variables)))))

(s/fdef compose
        :args (s/cat :doc  ::document
                     :docs (s/* ::document))
        :ret  ::document)

(defn compose
  "Compose multiple parsed documents together to create a single document."
  {:added "0.1.0"}
  [root-doc & docs]
  (apply merge-with into root-doc docs))

(s/fdef with-mapping
        :args (s/cat :doc     ::document
                     :mapping map?)
        :ret  ::document)

(defn with-mapping
  "Attaches an operation mapping to a document as meta data. An operation
  mapping maps a key to any named GraphQL operation within the document.
  Different keys can map to the same operation. When running an operation from
  the document, the operation mapping will be used to resolve multiple sets of
  variables based on the mapped key."
  {:added "0.1.0"}
  [doc mapping]
  (vary-meta doc assoc ::operation-mapping mapping))

(defn- resolve-fragments
  "Given a map of fragments, inline the values for a fragment if they appear
  within the selection set."
  [fragments sel-set]
  (if (empty? fragments)
    sel-set
    (reduce (fn [acc sel]
              (cond
                (= (:node-type sel) :fragment-spread)
                (->> (get-in fragments [(:name sel) :selection-set] [])
                     (resolve-fragments fragments)
                     (into acc))

                (not (nil? (:selection-set sel)))
                (conj acc (update sel :selection-set #(resolve-fragments fragments %)))

                :else
                (conj acc sel)))
            []
            sel-set)))

(defn- update-definitions [fragments definitions]
  (mapv #(update % :selection-set (partial resolve-fragments fragments))
        definitions))

(s/fdef inline-fragments
        :args (s/cat :doc ::document)
        :ret  ::document)

(defn inline-fragments
  "Given a parsed document that includes fragments, inline the values of the
  fragments directly into the query selection set(s)."
  {:added "0.1.0"}
  [doc]
  (let [fragments (->> (:fragment-definitions doc)
                       (map #(vector (:name %) %))
                       (into {}))]
    (if (empty? (:operation-definitions doc))
      (update doc :fragment-definitions #(update-definitions fragments %))
      (-> doc
          (update :operation-definitions #(update-definitions fragments %))
          (dissoc :fragment-definitions)))))

(defn- selection-of-data [sel-set root drop-unmatched?]
  (reduce (fn [acc sel]
            (case (:node-type sel)
              :field
              (let [kw     (some-> (:name sel (:field-name sel)) keyword)
                    gotten (kw root)]
                (if (and (nil? gotten) drop-unmatched?)
                  acc
                  (assoc acc
                         kw
                         (if-let [sel-set (:selection-set sel)]
                           (selection-of-data sel-set gotten drop-unmatched?)
                           gotten))))
              :inline-fragment
              (merge acc
                     (selection-of-data (:selection-set sel)
                                        root
                                        drop-unmatched?))

              acc))
          {}
          sel-set))

(s/fdef select
        :args (s/alt
                :arity-2 (s/cat :document ::document
                                :data     map?)
                :arity-3 (s/cat :document        ::document
                                :data            map?
                                :drop-unmatched? boolean?))
        :ret  map?)

(defn select
  "Given a query or fragment document and a map of data, select only the
  information in data that the document lists in its selection set."
  {:added "0.1.0"}
  ([doc data]
   (select doc data false))
  ([doc data drop-unmatched?]
   (let [sel-set (-> (some #(when-not (nil? %) %)
                           ((juxt :fragment-definitions
                                  :operation-definitions)
                            (inline-fragments doc)))
                     first
                     :selection-set)]
     (selection-of-data sel-set data drop-unmatched?))))

#?(:clj (do

(defn parse [source]
  (w/prewalk box/box->val
             (parser/parse
               ;; This is a hack because graphql-clj doesn't suppport parsing the
               ;; subscription operation type
               (string/replace source #"subscription" "query"))))

(defmacro parse-document
  "Parses a GraphQL query string and emits an AST representation of the source
  as Clojure data."
  {:added "0.1.0"}
  [source]
  (let [parsed (parse source)]
    (if (insta/failure? parsed)
      (let [{:keys [error loc]} (parser/parse-error parsed)]
        (throw (ex-info error {:reason   ::invalid-source
                               :location loc})))
      parsed)))

(defn- read-file [file]
  (slurp
   (condp instance? file
     java.io.File file
     java.net.URL file
     (or (io/resource file) file))))

(defmacro parse-document-files
  "Parses GraphQL files and emits an AST representation of the source as
  Clojure data."
  {:added "0.1.0"}
  [& files]
  (let [document (string/join "\n" (map read-file files))]
    `(artemis.document/parse-document ~document)))

;; end CLJ
))
