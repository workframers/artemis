(ns artemis.document
  #?(:cljs (:require-macros artemis.document))
  (:require [clojure.spec.alpha :as s]
            #?@(:clj [[graphql-builder.core :as gql]
                      [graphql-clj.parser :as parser]
                      [graphql-clj.box :as box]
                      [instaparse.core :as insta]
                      [clojure.walk :as w]])))

(defrecord
  ^{:added "0.1.0"}
  Document
  [ast source])

(s/fdef doc?
        :args (s/cat :x any?)
        :ret  boolean?)

(defn doc?
  "Returns `true` if `x` is a GraphQL document."
  {:added "0.1.0"}
  [x]
  (instance? Document x))

(s/def ::document doc?)
(s/def ::source string?)
(s/def ::ast vector?)

(s/fdef source
        :args (s/cat :document ::document)
        :ret  (s/or :nil    nil?
                    :source ::source))

(defn source
  "Returns the string source for a GraphQL document."
  {:added "0.1.0"}
  [document]
  (:source document))

(s/fdef ast
        :args (s/cat :document ::document)
        :ret  (s/or :nil    nil?
                    :source ::ast))

(defn ast
  "Returns the parsed AST for a GraphQL document."
  {:added "0.1.0"}
  [document]
  (:ast document))

#?(:clj (do
   (def type-name-node
     {:node-type  :field
      :field-name "__typename"})

   (defn selection-set? [x]
     (and (vector? x)
          (= :selection-set (first x))))

   (defn type-nameable? [selection-set]
     (reduce (fn [flag x]
               (or (and (= (:node-type x) :field)
                        (not (contains? x :arguments)))
                   (reduced false)))
             false
             selection-set))

   (defn cons-type-name [[_ selection-set]]
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
       (parser/parse source)))))

#?(:clj
   (defmacro parse-document
     "Parses a GraphQL query string and emits a `Document` that contains the
     original source string and an AST representation of the source in EDN."
     {:added "0.1.0"}
     [source]
     (let [parsed (parse source)]
       (if (insta/failure? parsed)
         (let [{:keys [error loc]} (parser/parse-error parsed)]
           (throw (ex-info error {:reason   ::invalid-source
                                  :location loc})))
         (Document. parsed source)))))
