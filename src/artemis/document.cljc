(ns artemis.document
  #?(:cljs (:require-macros artemis.document))
  (:require [clojure.spec.alpha :as s]
            [graphql-builder.core :as gql]
            [camel-snake-kebab.core :refer [->kebab-case]]
            #?@(:clj [[graphql-clj.parser :as parser]
                      [graphql-clj.box :as box]
                      [instaparse.core :as insta]
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
                                :variables map?))
        :ret  ::operation)

(defn operation
  "Given a document and some variables, return a map that describes the
  operation to execute under the :graphql key and a function to neatly unpack
  the data that results from that operation under the :unpack key. Unpacking
  reifies operations that were namespaced as a result of applying an operation
  mapping."
  {:added "0.1.0"}
  ([document]
   (operation document {}))
  ([document variables]
   (if (single-operation? document)
     (let [op (-> document :operation-definitions first :operation-type)]
       ((get-in (gql/query-map document)
                [(keyword (:type op))
                 (keyword (->kebab-case (:name op)))])
        variables))
     (let [op-map (::operation-mapping (meta document) {})]
       ((gql/composed-query document op-map) variables)))))

(s/fdef compose
        :args (s/cat :doc  ::document
                     :docs (s/* ::document))
        :ret  ::document)

(defn compose
  "Compose multiple parsed documents together to create a single document."
  {:added "0.1.0"}
  [doc & docs]
  (apply merge-with into doc docs))

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

#?(:clj (do

(def ^:private type-name-node
 {:node-type  :field
  :field-name "__typename"})

(defn- selection-set? [x]
 (and (vector? x)
      (= :selection-set (first x))))

(defn- type-nameable? [selection-set]
 (reduce (fn [flag x]
           (or (and (= (:node-type x) :field)
                    (not (contains? x :arguments)))
               (reduced false)))
         false
         selection-set))

(defn- cons-type-name [[_ selection-set]]
 [:selection-set
  (if-not (type-nameable? selection-set)
    selection-set
    (vec (cons type-name-node selection-set)))])

(defn parse [source]
 (w/prewalk
   (fn [x]
     (cond-> x
       :always            box/box->val
       (selection-set? x) cons-type-name))
   (parser/parse source)))

(defmacro parse-document
 "Parses a GraphQL query string and emits an AST representation of the source
 in EDN."
 {:added "0.1.0"}
 [source]
 (let [parsed (parse source)]
   (if (insta/failure? parsed)
     (let [{:keys [error loc]} (parser/parse-error parsed)]
       (throw (ex-info error {:reason   ::invalid-source
                              :location loc})))
     parsed)))
))
