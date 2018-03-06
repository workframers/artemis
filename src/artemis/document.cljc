(ns artemis.document
  #?(:cljs (:require-macros artemis.document))
  (:require [clojure.spec.alpha :as s]
            #?@(:clj [[alumbra.parser :as a]
                      [alumbra.errors :as ae]
                      [clojure.walk :as w]])))

(defn- remove-namespace [x]
  (if (and (keyword? x) (namespace x))
    (keyword (name x))
    x))

(defrecord Document [ast source])

(s/fdef doc?
        :args (s/cat :x any?)
        :ret  boolean?)

(defn doc?
  "Returns true if x is a GraphQL document"
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
  [document]
  (:source document))

(s/fdef ast
        :args (s/cat :document ::document)
        :ret  (s/or :nil    nil?
                    :source ::ast))

(defn ast
  "Returns the alumbra-parsed AST for a GraphQL document."
  [document]
  (:ast document))

#?(:clj
   (defmacro parse-document
     "Parses a GraphQL query string and returns a GrqphQL document that
     contains the original source string and an ast representation of the
     source."
     [source]
     (let [parsed (a/parse-document source)]
       (if (contains? parsed :alumbra/parser-errors)
         (throw (ex-info "Unable to parse source."
                         {:reason  ::invalid-source
                          :errors  (vec (ae/explain-data parsed source))}))
         (let [ast (w/postwalk remove-namespace parsed)]
           (Document. ast source))))))
