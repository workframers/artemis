(ns artemis.stores.mapgraph-store-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [artemis.document :as d]
            [artemis.stores.mapgraph-store :refer [create-store write-to-cache]]))

(def test-queries
  {:basic
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :artemis.stores.mapgraph-store/cache :root}}}

   :args
   {:query    (d/parse-document
                "{
                   id
                   stringField(arg: 1)
                   numberField
                   nullField
                 }")
    :result   {:id          "abcd"
               :stringField "The arg was 1"
               :numberField 3
               :nullField   nil}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                "stringField({\"arg\":1})"           "The arg was 1"
                :numberField                         3
                :nullField                           nil
                :artemis.stores.mapgraph-store/cache :root}}}

   :aliased
   {:query    (d/parse-document
                "{
                   id
                   aliasedField: stringField
                   numberField
                   nullField
                 }")
    :result   {:id           "abcd"
               :aliasedField "this is a string"
               :numberField  3
               :nullField    nil}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :artemis.stores.mapgraph-store/cache :root}}}

   :aliased-with-args
   {:query    (d/parse-document
                "{
                   id
                   aliasedField1: stringField(arg:1)
                   aliasedField2: stringField(arg:2)
                   numberField
                   nullField
                 }")
    :result   {:id            "abcd"
               :aliasedField1 "The arg was 1"
               :aliasedField2 "The arg was 2"
               :numberField   3
               :nullField     nil}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                "stringField({\"arg\":1})"           "The arg was 1"
                "stringField({\"arg\":2})"           "The arg was 2"
                :numberField                         3
                :nullField                           nil
                :artemis.stores.mapgraph-store/cache :root}}}

   :with-vars
   {:query      (d/parse-document
                  "{
                     id
                     stringField(arg: $stringArg)
                     numberField(intArg: $intArg, floatArg: $floatArg)
                     nullField
                   }")
    :input-vars {:intArg    5
                 :floatArg  3.14
                 :stringArg "This is a string"}
    :result     {:id          "abcd"
                 :stringField "This worked"
                 :numberField 5
                 :nullField   nil}
    :entities   {[:artemis.stores.mapgraph-store/cache :root]
                 {:id                                             "abcd"
                  :nullField                                      nil
                  "numberField({\"intArg\":5,\"floatArg\":3.14})" 5
                  "stringField({\"arg\":\"This is a string\"})"   "This worked"
                  :artemis.stores.mapgraph-store/cache            :root}}}

   :default-vars
   {:query      (d/parse-document
                  "query someBigQuery(
                     $stringArg: String = \"This is a default string\"
                     $intArg: Int
                     $floatArg: Float
                   ) {
                     id
                     stringField(arg: $stringArg)
                     numberField(intArg: $intArg, floatArg: $floatArg)
                     nullField
                   }")
    :input-vars {:intArg   5
                 :floatArg 3.14}
    :result     {:id          "abcd"
                 :stringField "This worked"
                 :numberField 5
                 :nullField   nil}
    :entities   {[:artemis.stores.mapgraph-store/cache :root]
                 {:id                                                   "abcd"
                  :nullField                                            nil
                  "numberField({\"intArg\":5,\"floatArg\":3.14})"       5
                  "stringField({\"arg\":\"This is a default string\"})" "This worked"
                  :artemis.stores.mapgraph-store/cache                  :root}}}

   :directives
   {:query    (d/parse-document
                "{
                   id
                   firstName @include(if: true)
                   lastName @upperCase
                   birthDate @dateFormat(format: \"DD-MM-YYYY\")
                 }")
    :result   {:id        "abcd"
               :firstName "James"
               :lastName  "BOND"
               :birthDate "20-05-1940"}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                                 "abcd"
                :firstName                                          "James"
                "lastName@upperCase"                                "BOND"
                "birthDate@dateFormat({\"format\":\"DD-MM-YYYY\"})" "20-05-1940"
                :artemis.stores.mapgraph-store/cache                :root}}}

   :nested
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedObj {
                     id
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   {:id          "abcde"
                             :stringField "this is a string too"
                             :numberField 3
                             :nullField   nil
                             :__typename  "object"}}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :nestedObj                           [:object/id "abcde"]
                :artemis.stores.mapgraph-store/cache :root}
               [:object/id "abcde"]
               {:object/id          "abcde"
                :object/stringField "this is a string too"
                :object/numberField 3
                :object/nullField   nil}}}

   :nested-no-id
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedObj {
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   {:stringField "this is a string too"
                             :numberField 3
                             :nullField   nil
                             :__typename  "object"}}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :nestedObj                           [:artemis.stores.mapgraph-store/cache "root.nestedObj"]
                :artemis.stores.mapgraph-store/cache :root}
               [:artemis.stores.mapgraph-store/cache "root.nestedObj"]
               {:object/stringField                  "this is a string too"
                :object/numberField                  3
                :object/nullField                    nil
                :artemis.stores.mapgraph-store/cache "root.nestedObj"}}}

   :nested-with-args
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedObj(arg:\"val\") {
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   {:stringField "this is a string too"
                             :numberField 3
                             :nullField   nil
                             :__typename  "object"}}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                "nestedObj(arg:\"val\")"             [:artemis.stores.mapgraph-store/cache "root.nestedObj(arg:\"val\")"]
                :artemis.stores.mapgraph-store/cache :root}
               [:artemis.stores.mapgraph-store/cache "root.nestedObj(arg:\"val\")"]
               {:object/stringField                  "this is a string too"
                :object/numberField                  3
                :object/nullField                    nil
                :artemis.stores.mapgraph-store/cache "root.nestedObj(arg:\"val\")"}}}

   :nested-array
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedArray {
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:id          "abcde"
                              :stringField "this is a string too"
                              :numberField 2
                              :nullField   nil
                              :__typename  "object"}
                             {:id          "abcdef"
                              :stringField "this is a string also"
                              :numberField 3
                              :nullField   nil
                              :__typename  "object"}]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         1
                :nullField                           nil
                :nestedArray                         [[:object/id "abcde"]
                                                      [:object/id "abcdef"]]
                :artemis.stores.mapgraph-store/cache :root}
               [:object/id "abcde"]
               {:object/id          "abcde"
                :object/stringField "this is a string too"
                :object/numberField 2
                :object/nullField   nil}
               [:object/id "abcdef"]
               {:object/id          "abcdef"
                :object/stringField "this is a string also"
                :object/numberField 3
                :object/nullField   nil}}}

   :nested-array-with-null
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedArray {
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:id          "abcde"
                              :stringField "this is a string too"
                              :numberField 2
                              :nullField   nil
                              :__typename  "object"}
                             nil]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         1
                :nullField                           nil
                :nestedArray                         [[:object/id "abcde"] nil]
                :artemis.stores.mapgraph-store/cache :root}
               [:object/id "abcde"]
               {:object/id          "abcde"
                :object/stringField "this is a string too"
                :object/numberField 2
                :object/nullField   nil}}}

   :nested-array-without-ids
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedArray {
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:stringField "this is a string too"
                              :numberField 2
                              :nullField   nil
                              :__typename  "object"}
                             {:stringField "this is a string also"
                              :numberField 3
                              :nullField   nil
                              :__typename  "object"}]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         1
                :nullField                           nil
                :nestedArray                         [[:artemis.stores.mapgraph-store/cache "root.nestedArray.0"]
                                                      [:artemis.stores.mapgraph-store/cache "root.nestedArray.1"]]
                :artemis.stores.mapgraph-store/cache :root}
               [:artemis.stores.mapgraph-store/cache "root.nestedArray.0"]
               {:object/stringField                  "this is a string too"
                :object/numberField                  2
                :object/nullField                    nil
                :artemis.stores.mapgraph-store/cache "root.nestedArray.0"}
               [:artemis.stores.mapgraph-store/cache "root.nestedArray.1"]
               {:object/stringField                  "this is a string also"
                :object/numberField                  3
                :object/nullField                    nil
                :artemis.stores.mapgraph-store/cache "root.nestedArray.1"}}}

   :nested-array-with-nulls-and-no-ids
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedArray {
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [nil
                             {:stringField "this is a string also"
                              :numberField 2
                              :nullField   nil
                              :__typename  "object"}]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         1
                :nullField                           nil
                :nestedArray                         [nil
                                                      [:artemis.stores.mapgraph-store/cache "root.nestedArray.1"]]
                :artemis.stores.mapgraph-store/cache :root}
               [:artemis.stores.mapgraph-store/cache "root.nestedArray.1"]
               {:object/stringField                  "this is a string also"
                :object/numberField                  3
                :object/nullField                    nil
                :artemis.stores.mapgraph-store/cache "root.nestedArray.1"}}}

   :simple-array
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   simpleArray
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :simpleArray ["one" "two" "three"]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :simpleArray                         ["one" "two" "three"]
                :artemis.stores.mapgraph-store/cache :root}}}

   :simple-array-with-nulls
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   simpleArray
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :simpleArray [nil "two" "three"]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :simpleArray                         [nil "two" "three"]
                :artemis.stores.mapgraph-store/cache :root}}}

   :obj-in-different-paths
   {:query    (d/parse-document
                "{
                   id
                   object1 {
                     id
                     stringField
                     __typename
                   }
                   object2 {
                     id
                     numberField
                     __typename
                   }
                 }")
    :result   {:id      "a"
               :object1 {:id          "aa"
                         :stringField "this is a string"
                         :__typename  "object"}
               :object2 {:id          "aa"
                         :numberField 1
                         :__typename  "object"}}

    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "a"
                :array1                              [[:object/id "aa"]]
                :array2                              [[:object/id "ab"]]
                :artemis.stores.mapgraph-store/cache :root}
               [:object/id "aa"]
               {:object/id          "aa"
                :object/stringField "this is a string"
                :numberField        1}}}

   :obj-in-different-array-paths
   {:query    (d/parse-document
                "{
                   id
                   array1 {
                     id
                     stringField
                     obj {
                       id
                       stringField
                       __typename
                     }
                     __typename
                   }
                   array2 {
                     id
                     stringField
                     obj {
                       id
                       numberField
                       __typename
                     }
                     __typename
                   }
                 }")
    :result   {:id     "a"
               :array1 [{:id          "aa"
                         :stringField "this is a string"
                         :obj         {:id          "aaa"
                                       :stringField "string"
                                       :__typename  "nested-object"}
                         :__typename  "object"}]
               :array2 [{:id          "ab"
                         :stringField "this is a string too"
                         :obj         {:id          "aaa"
                                       :numberField 1
                                       :__typename  "nested-object"}
                         :__typename  "object"}]}

    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "a"
                :array1                              [[:object/id "aa"]]
                :array2                              [[:object/id "ab"]]
                :artemis.stores.mapgraph-store/cache :root}
               [:object/id "aa"]
               {:object/id          "aa"
                :object/stringField "this is a string"
                :obj                [:nested-object/id "aaa"]}
               [:object/id "ab"]
               {:object/id          "ab"
                :object/stringField "this is a string too"
                :obj                [:nested-object/id "aaa"]}
               [:nested-object/id "aaa"]
               {:nested-object/id   "aaa"
                :object/stringField "string"
                :numberField        1}}}

   :obj-twice-in-same-array-path
   {:query    (d/parse-document
                " {
                   id
                   array1 {
                     id
                     stringField
                     obj {
                       id
                       stringField
                       numberField
                       __typename
                     }
                     __typename
                   }
                 }")
    :result   {:id     "a"
               :array1 [{:id          "aa"
                         :stringField "this is a string"
                         :obj         {:id          "aaa"
                                       :stringField "string"
                                       :numberField 1
                                       :__typename  "nested-object"}
                         :__typename  "object"}
                        {:id          "ab"
                         :stringField "this is a string too"
                         :obj         {:id          "aaa"
                                       :stringField "should not be written?" ;; not sure if we should enforce this guarantee
                                       :numberField 2
                                       :__typename  "nested-object"}
                         :__typename  "object"}]}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "a"
                :array1                              [[:object/id "aa"]
                                                      [:object/id "ab"]]
                :artemis.stores.mapgraph-store/cache :root}
               [:object/id "aa"]
               {:object/id          "aa"
                :object/stringField "this is a string"
                :obj                [:nested-object/id "aaa"]}
               [:object/id "ab"]
               {:object/id          "ab"
                :object/stringField "this is a string too"
                :obj                [:nested-object/id "aaa"]}
               [:nested-object/id "aaa"]
               {:nested-object/id   "aaa"
                :object/stringField "string"
                :numberField        1}}}

   :nested-object-returning-null
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedObj {
                     id
                     stringField
                     numberField
                     nullField
                     __typename
                   }
                   __typename
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   nil}
    :entities {[:artemis.stores.mapgraph-store/cache :root]
               {:id                                  "abcd"
                :stringField                         "this is a string"
                :numberField                         3
                :nullField                           nil
                :nestedObj                           nil
                :artemis.stores.mapgraph-store/cache :root}}}})

(defn query-test [k]
  (testing (str "testing normalized cache persistence for query type: " k)
    (let [{:keys [query input-vars result entities]} (get test-queries k)
          new-cache (write-to-cache query result
                                    {:input-vars input-vars
                                     :store (create-store {:id-attrs #{:object/id :nested-object/id}})})]
      (is (= entities (:entities new-cache))))))

(deftest test-query-persistence
  (doall (map query-test (keys test-queries))))
