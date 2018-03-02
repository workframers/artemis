(ns artemis.stores.mapgraph-store-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [artemis.document :as d]))
;
;(def test-queries [:aliased :aliased-with-args :arrays-with-non-objects :basic :custom-directives :nested :nested-array
;                   :nested-with-args :objs-in-diff-paths :same-obj-array-twice :vars-with-defaults :with-vars])
;
;(defn test-graphql-doc [k]
;  (-> k
;      name
;      (str ".graphql")
;      slurp
;      d/parse-document))
;
;(def test-query-map (into {} (map #(vector % (test-graphql-doc %)))))
;
;(println "maphraph test")

(defonce test-queries
  {:basic {:query  (d/parse-document
                     "{
                        id
                        stringField
                        numberField
                        nullField
                      }")
           :result {:id "abcd"
                    :stringField "this is a string"
                    :numberField 3
                    :nullField nil}}})

(deftest basic-query-test
  (testing "basic queries test"
    (is (= test-queries {}))))
