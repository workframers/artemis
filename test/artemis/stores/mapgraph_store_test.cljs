(ns artemis.stores.mapgraph-store-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [artemis.core :as a]
            [artemis.document :as d]
            [artemis.stores.mapgraph.core :refer [create-store]]
            [artemis.logging :as logging :refer [warn]]))

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                ::cache      [:artemis.mapgraph/generated "root"]}}}

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id                        "abcd"
                "stringField({\"arg\":1})" "The arg was 1"
                :numberField               3
                :nullField                 nil
                ::cache                    [:artemis.mapgraph/generated "root"]}}}

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id           "abcd"
                :aliasedField "this is a string"
                :numberField  3
                :nullField    nil
                ::cache       [:artemis.mapgraph/generated "root"]}}}

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id                          "abcd"
                "aliasedField1({\"arg\":1})" "The arg was 1"
                "aliasedField2({\"arg\":2})" "The arg was 2"
                :numberField                 3
                :nullField                   nil
                ::cache                      [:artemis.mapgraph/generated "root"]}}}

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
    :entities   {[:artemis.mapgraph/generated "root"]
                 {:id                                             "abcd"
                  :nullField                                      nil
                  "numberField({\"intArg\":5,\"floatArg\":3.14})" 5
                  "stringField({\"arg\":\"This is a string\"})"   "This worked"
                  ::cache                                         [:artemis.mapgraph/generated "root"]}}}

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
    :entities   {[:artemis.mapgraph/generated "root"]
                 {:id                                                   "abcd"
                  :nullField                                            nil
                  "numberField({\"intArg\":5,\"floatArg\":3.14})"       5
                  "stringField({\"arg\":\"This is a default string\"})" "This worked"
                  ::cache                                               [:artemis.mapgraph/generated "root"]}}}

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id                                                 "abcd"
                :firstName                                          "James"
                "lastName@upperCase"                                "BOND"
                "birthDate@dateFormat({\"format\":\"DD-MM-YYYY\"})" "20-05-1940"
                ::cache                                             [:artemis.mapgraph/generated "root"]}}}

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
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   {:id          "abcde"
                             :stringField "this is a string too"
                             :numberField 3
                             :nullField   nil}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                :nestedObj   {:artemis.mapgraph/ref "abcde"}
                ::cache      [:artemis.mapgraph/generated "root"]}
               "abcde"
               {:id          "abcde"
                :stringField "this is a string too"
                :numberField 3
                :nullField   nil}}}

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
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   {:stringField "this is a string too"
                             :numberField 3
                             :nullField   nil}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                :nestedObj   {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedObj"]}
                ::cache      [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.nestedObj"]
               {:stringField "this is a string too"
                :numberField 3
                :nullField   nil
                ::cache      [:artemis.mapgraph/generated "root.nestedObj"]}}}

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
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   {:stringField "this is a string too"
                             :numberField 3
                             :nullField   nil}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id                            "abcd"
                :stringField                   "this is a string"
                :numberField                   3
                :nullField                     nil
                "nestedObj({\"arg\":\"val\"})" {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedObj({\"arg\":\"val\"})"]}
                ::cache                        [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.nestedObj({\"arg\":\"val\"})"]
               {:stringField "this is a string too"
                :numberField 3
                :nullField   nil
                ::cache      [:artemis.mapgraph/generated "root.nestedObj({\"arg\":\"val\"})"]}}}

   :nested-array
   {:query    (d/parse-document
               "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedArray {
                     id
                     stringField
                     numberField
                     nullField
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:id          "abcde"
                              :stringField "this is a string too"
                              :numberField 2
                              :nullField   nil}
                             {:id          "abcdef"
                              :stringField "this is a string also"
                              :numberField 3
                              :nullField   nil}]}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 1
                :nullField   nil
                :nestedArray [{:artemis.mapgraph/ref "abcde"}
                              {:artemis.mapgraph/ref "abcdef"}]
                ::cache      [:artemis.mapgraph/generated "root"]}
               "abcde"
               {:id          "abcde"
                :stringField "this is a string too"
                :numberField 2
                :nullField   nil}
               "abcdef"
               {:id          "abcdef"
                :stringField "this is a string also"
                :numberField 3
                :nullField   nil}}}

   :nested-array-with-null
   {:query    (d/parse-document
               "{
                   id
                   stringField
                   numberField
                   nullField
                   nestedArray {
                     id
                     stringField
                     numberField
                     nullField
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:id          "abcde"
                              :stringField "this is a string too"
                              :numberField 2
                              :nullField   nil}
                             nil]}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 1
                :nullField   nil
                :nestedArray [{:artemis.mapgraph/ref "abcde"} nil]
                ::cache      [:artemis.mapgraph/generated "root"]}
               "abcde"
               {:id          "abcde"
                :stringField "this is a string too"
                :numberField 2
                :nullField   nil}}}

   :deeply-nested-array
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
                     deeplyNestedArray {
                       numberField
                       stringField
                     }
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:stringField       "this is a string too"
                              :numberField       2
                              :nullField         nil
                              :deeplyNestedArray [{:numberField 10
                                                   :stringField "Foo"}
                                                  {:numberField 20
                                                   :stringField "Bar"}]}
                             {:stringField       "this is a string also"
                              :numberField       3
                              :deeplyNestedArray [{:numberField 30
                                                   :stringField "Baz"}
                                                  {:numberField 40
                                                   :stringField "Boo"}]
                              :nullField         nil}

                             {:stringField       "this is a string, man"
                              :numberField       6
                              :deeplyNestedArray []
                              :nullField         nil}]}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 1
                :nullField   nil
                :nestedArray [{:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.0"]}
                              {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.1"]}
                              {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.2"]}]
                ::cache      [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.nestedArray.0"]
               {:stringField       "this is a string too"
                :numberField       2
                :nullField         nil
                :deeplyNestedArray [{:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.0.deeplyNestedArray.0"]}
                                    {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.0.deeplyNestedArray.1"]}]
                ::cache            [:artemis.mapgraph/generated "root.nestedArray.0"]}
               [:artemis.mapgraph/generated "root.nestedArray.1"]
               {:stringField       "this is a string also"
                :numberField       3
                :nullField         nil
                :deeplyNestedArray [{:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.1.deeplyNestedArray.0"]}
                                    {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.1.deeplyNestedArray.1"]}]
                ::cache            [:artemis.mapgraph/generated "root.nestedArray.1"]}
               [:artemis.mapgraph/generated "root.nestedArray.2"]
               {:stringField       "this is a string, man"
                :numberField       6
                :nullField         nil
                :deeplyNestedArray []
                ::cache            [:artemis.mapgraph/generated "root.nestedArray.2"]}
               [:artemis.mapgraph/generated "root.nestedArray.0.deeplyNestedArray.0"]
               {:numberField 10
                :stringField "Foo"
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.0.deeplyNestedArray.0"]}
               [:artemis.mapgraph/generated "root.nestedArray.0.deeplyNestedArray.1"]
               {:numberField 20
                :stringField "Bar"
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.0.deeplyNestedArray.1"]}
               [:artemis.mapgraph/generated "root.nestedArray.1.deeplyNestedArray.0"]
               {:numberField 30
                :stringField "Baz"
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.1.deeplyNestedArray.0"]}
               [:artemis.mapgraph/generated "root.nestedArray.1.deeplyNestedArray.1"]
               {:numberField 40
                :stringField "Boo"
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.1.deeplyNestedArray.1"]}}}

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
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [{:stringField "this is a string too"
                              :numberField 2
                              :nullField   nil}
                             {:stringField "this is a string also"
                              :numberField 3
                              :nullField   nil}]}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 1
                :nullField   nil
                :nestedArray [{:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.0"]}
                              {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.1"]}]
                ::cache      [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.nestedArray.0"]
               {:stringField "this is a string too"
                :numberField 2
                :nullField   nil
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.0"]}
               [:artemis.mapgraph/generated "root.nestedArray.1"]
               {:stringField "this is a string also"
                :numberField 3
                :nullField   nil
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.1"]}}}

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
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 1
               :nullField   nil
               :nestedArray [nil
                             {:stringField "this is a string also"
                              :numberField 3
                              :nullField   nil}]}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 1
                :nullField   nil
                :nestedArray [nil
                              {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.nestedArray.1"]}]
                ::cache      [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.nestedArray.1"]
               {:stringField "this is a string also"
                :numberField 3
                :nullField   nil
                ::cache      [:artemis.mapgraph/generated "root.nestedArray.1"]}}}

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                :simpleArray ["one" "two" "three"]
                ::cache      [:artemis.mapgraph/generated "root"]}}}

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
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                :simpleArray [nil "two" "three"]
                ::cache      [:artemis.mapgraph/generated "root"]}}}

   :obj-in-different-paths
   {:query    (d/parse-document
               "{
                   id
                   object1 {
                     id
                     stringField
                   }
                   object2 {
                     id
                     numberField
                   }
                 }")
    :result   {:id      "a"
               :object1 {:id          "aa"
                         :stringField "this is a string"}
               :object2 {:id          "aa"
                         :numberField 1}}

    :entities {[:artemis.mapgraph/generated "root"]
               {:id      "a"
                :object1 {:artemis.mapgraph/ref "aa"}
                :object2 {:artemis.mapgraph/ref "aa"}
                ::cache  [:artemis.mapgraph/generated "root"]}
               "aa"
               {:id          "aa"
                :stringField "this is a string"
                :numberField 1}}}

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
                     }
                   }
                   array2 {
                     id
                     stringField
                     obj {
                       id
                       numberField
                     }
                   }
                 }")
    :result   {:id     "a"
               :array1 [{:id          "aa"
                         :stringField "this is a string"
                         :obj         {:id          "aaa"
                                       :stringField "string"}}]
               :array2 [{:id          "ab"
                         :stringField "this is a string too"
                         :obj         {:id          "aaa"
                                       :numberField 1}}]}

    :entities {[:artemis.mapgraph/generated "root"]
               {:id     "a"
                :array1 [{:artemis.mapgraph/ref "aa"}]
                :array2 [{:artemis.mapgraph/ref "ab"}]
                ::cache [:artemis.mapgraph/generated "root"]}
               "aa"
               {:id          "aa"
                :stringField "this is a string"
                :obj         {:artemis.mapgraph/ref "aaa"}}
               "ab"
               {:id          "ab"
                :stringField "this is a string too"
                :obj         {:artemis.mapgraph/ref "aaa"}}
               "aaa"
               {:id          "aaa"
                :stringField "string"
                :numberField 1}}}

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
                   }
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil
               :nestedObj   nil}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                :nestedObj   nil
                ::cache      [:artemis.mapgraph/generated "root"]}}}

   :union-array
   {:query    (d/parse-document
               "{
                     search(text: \"a\") {
                       ... on object {
                         id
                         stringField
                         __typename
                       }
                       ... on otherobject {
                         id
                         numberField
                         __typename
                       }
                     }
                   }")
    :result   {:search
               [{:id          "abcd"
                 :stringField "this is a string"
                 :__typename  "object"}
                {:id          "efgh"
                 :numberField 3
                 :__typename  "otherobject"}]}
    :entities {[:artemis.mapgraph/generated "root"]
               {"search({\"text\":\"a\"})" [{:artemis.mapgraph/ref "abcd"}
                                            {:artemis.mapgraph/ref "efgh"}]
                ::cache                    [:artemis.mapgraph/generated "root"]}
               "abcd"
               {:id          "abcd"
                :stringField "this is a string"
                :__typename  "object"}
               "efgh"
               {:id          "efgh"
                :numberField 3
                :__typename  "otherobject"}}}

   :union-array-no-id
   {:query    (d/parse-document
               "{
                     search(text: \"a\") {
                       ... on someobject {
                         stringField
                         __typename
                       }
                     }
                   }")
    :result   {:search
               [{:stringField "this is a string"
                 :__typename  "someobject"}
                {:stringField "this is another string"
                 :__typename  "someobject"}]}
    :entities {[:artemis.mapgraph/generated "root"]
               {"search({\"text\":\"a\"})" [{:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.search({\"text\":\"a\"}).0"]}
                                            {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.search({\"text\":\"a\"}).1"]}]
                ::cache                    [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.search({\"text\":\"a\"}).0"]
               {:stringField "this is a string"
                :__typename  "someobject"
                ::cache      [:artemis.mapgraph/generated "root.search({\"text\":\"a\"}).0"]}
               [:artemis.mapgraph/generated "root.search({\"text\":\"a\"}).1"]
               {:stringField "this is another string"
                :__typename  "someobject"
                ::cache      [:artemis.mapgraph/generated "root.search({\"text\":\"a\"}).1"]}}}

   :nested-union
   {:query    (d/parse-document
               "{
                     id
                     stringField
                     unionObj {
                       ... on object {
                         id
                         numberField
                         stringField
                         __typename
                       }
                       ... on otherobject {
                         id
                         stringField
                         __typename
                       }
                     }
                   }")
    :result   {:id          "a"
               :stringField "this is a string"
               :unionObj    {:id          "abcd"
                             :stringField "this is a string"
                             :numberField 3
                             :__typename  "object"}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "a"
                :stringField "this is a string"
                :unionObj    {:artemis.mapgraph/ref "abcd"}
                ::cache      [:artemis.mapgraph/generated "root"]}
               "abcd"
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :__typename  "object"}}}

   :nested-union-typename-in-outer-level
   {:query    (d/parse-document
               "{
                     id
                     stringField
                     unionObj {
                       __typename
                       ... on object {
                         id
                         numberField
                         stringField
                       }
                       ... on otherobject {
                         id
                         stringField
                       }
                     }
                   }")
    :result   {:id          "a"
               :stringField "this is a string"
               :unionObj    {:id          "abcd"
                             :stringField "this is a string"
                             :numberField 3
                             :__typename  "object"}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "a"
                :stringField "this is a string"
                :unionObj    {:artemis.mapgraph/ref "abcd"}
                ::cache      [:artemis.mapgraph/generated "root"]}
               "abcd"
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :__typename  "object"}}}

   :nested-union-no-id
   {:query    (d/parse-document
               "{
                     id
                     stringField
                     unionObj {
                       ... on someobject {
                         numberField
                         stringField
                         __typename
                       }
                       ... on someotherobject {
                         stringField
                         __typename
                       }
                     }
                   }")
    :result   {:id          "a"
               :stringField "this is a string"
               :unionObj    {:stringField "this is a string"
                             :numberField 3
                             :__typename  "someobject"}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "a"
                :stringField "this is a string"
                :unionObj    {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.unionObj"]}
                ::cache      [:artemis.mapgraph/generated "root"]}
               [:artemis.mapgraph/generated "root.unionObj"]
               {:stringField "this is a string"
                :numberField 3
                :__typename  "someobject"
                ::cache      [:artemis.mapgraph/generated "root.unionObj"]}}}

   :nested-union-null
   {:query    (d/parse-document
               "{
                     id
                     stringField
                     unionObj {
                       ... on someobject {
                         numberField
                         stringField
                         __typename
                       }
                       ... on someotherobject {
                         stringField
                         __typename
                       }
                     }
                   }")
    :result   {:id          "a"
               :stringField "this is a string"
               :unionObj    nil}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "a"
                :stringField "this is a string"
                :unionObj    nil
                ::cache      [:artemis.mapgraph/generated "root"]}}}

   :fragments
   {:query    (d/parse-document
               "query SomeQuery {
                   id
                   ...OtherFields
                 }

                 fragment OtherFields on object {
                   stringField
                   numberField
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                ::cache      [:artemis.mapgraph/generated "root"]}}}

   :nested-fragments
   {:query    (d/parse-document
               "query SomeQuery {
                   id
                   nestedObj {
                     id
                     ...OtherFields
                   }
                 }

                 fragment OtherFields on object {
                   stringField
                   numberField
                 }")
    :result   {:id        "abcd"
               :nestedObj {:id          "abcde"
                           :stringField "this is a string"
                           :numberField 3}}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id        "abcd"
                :nestedObj {:artemis.mapgraph/ref "abcde"}
                ::cache    [:artemis.mapgraph/generated "root"]}
               "abcde"
               {:id          "abcde"
                :stringField "this is a string"
                :numberField 3}}}

   :chained-fragments
   {:query    (d/parse-document
               "query SomeQuery {
                   id
                   ...OtherFields
                 }

                 fragment OtherFields on object {
                   stringField
                   ...EvenOtherFields
                 }

                fragment EvenOtherFields on object {
                   numberField
                }
                ")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3}
    :entities {[:artemis.mapgraph/generated "root"]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                ::cache      [:artemis.mapgraph/generated "root"]}}}})

(defn write-test [k]
  (testing (str "testing normalized cache persistence for query type: " k)
    (let [{:keys [query input-vars result entities]} (get test-queries k)
          initial-store (create-store :cache-key ::cache)
          new-store (a/write initial-store {:data result} query input-vars)]
      (is (= entities (:entities new-store))))))

(deftest test-cache-persistence
  (doseq [test-query (keys test-queries)]
    (write-test test-query)))

(defn read-test [k]
  (testing (str "testing normalized cache querying for query type: " k)
    (let [{:keys [query input-vars result entities]} (get test-queries k)
          store (create-store :entities entities
                              :cache-key ::cache)
          response (a/read store query input-vars)]
      (is (= {:data result
              :partial? false} response)))))

(deftest test-cache-reading
  (doseq [test-query (keys test-queries)]
    (read-test test-query)))

(def test-fragments
  {:basic
   {:fragment    (d/parse-document
                  "fragment A on object {
                      stringField
                    }")
    :entities    {"abcde"
                  {:id          "abcde"
                   :stringField "this is a string"
                   :numberField 3
                   :nullField   nil
                   ::cache      [:artemis.mapgraph/generated "root"]}}
    :entity      "abcde"
    :write-data  {:stringField "this is a different string"}
    :read-result {:stringField "this is a string"}}

   :nested-fragments
   {:fragment    (d/compose
                  (d/parse-document
                   "fragment A on B {
                     slug
                     friends {
                       ...C
                     }
                   }")
                  (d/parse-document
                   "fragment C on D {
                     slug
                     name
                     address {
                       ...D
                     }
                   }")
                  (d/parse-document
                   "fragment D on E {
                      zip
                    }"))
    :entities    {[:slug "abc"]
                  {:slug    "abc"
                   :friends [{:artemis.mapgraph/ref [:slug "abcd"]}
                             {:artemis.mapgraph/ref [:slug "abcde"]}]}

                  [:slug "abcd"]
                  {:slug    "abcd"
                   :name    "fudge master"
                   :address {:artemis.mapgraph/ref 1}}

                  1
                  {:id 1 :street "test 1" :zip 11222}

                  2
                  {:id 2 :street "test 2" :zip 03062}

                  [:slug "abcde"]
                  {:slug    "abcde"
                   :name    "fudge colonel"
                   :address {:artemis.mapgraph/ref 2}}}
    :entity      [:slug "abc"]
    :read-result {:slug    "abc"
                  :friends [{:slug "abcd" :name "fudge master" :address {:zip 11222}}
                            {:slug "abcde" :name "fudge colonel" :address {:zip 03062}}]}}

   :multiple-fields
   {:fragment    (d/parse-document
                  "fragment A on object {
                      numberField
                      stringField
                    }")
    :entities    {"abcde"
                  {:id          "abcde"
                   :stringField "this is a string"
                   :numberField 3}}
    :entity      "abcde"
    :write-data  {:stringField "this is a different string"
                  :numberField 4}
    :read-result {:stringField "this is a string"
                  :numberField 3}}

   :fields-with-args
   {:fragment    (d/parse-document
                  "fragment A on object {
                      argField(arg: 1)
                    }")
    :entities    {"abcde"
                  {:id                     "abcde"
                   "argField({\"arg\":1})" "this is a value 1"
                   "argField({\"arg\":2})" "this is a value 2"}}
    :entity      "abcde"
    :write-data  {:argField "this is a different value"}
    :read-result {:argField "this is a value 1"}}

   :fields-with-arg-vars
   {:fragment    (d/parse-document
                  "fragment A on object {
                      argField(arg: $argument)
                    }")
    :entities    {"abcde"
                  {:id                     "abcde"
                   "argField({\"arg\":1})" "this is a value 1"
                   "argField({\"arg\":2})" "this is a value 2"}}
    :entity      "abcde"
    :variables   {:argument 2}
    :write-data  {:argField "this is a different value"}
    :read-result {:argField "this is a value 2"}}})

(defn write-fragment-test [k]
  (testing (str "testing normalized cache persistence for fragment type: " k)
    (let [{:keys [fragment entity write-data entities]} (get test-fragments k)
          initial-store (create-store :entities entities
                                      :cache-key ::cache)
          old-ent (get (:entities initial-store) entity)
          new-store (a/write-fragment initial-store {:data write-data} fragment entity)
          new-ent (get (:entities new-store) entity)]
      (is (= new-ent (merge old-ent new-ent))))))

(deftest test-cache-fragment-persistence
  (doseq [test-fragment (keys test-fragments)]
    (write-fragment-test test-fragment)))

(defn read-fragment-test [k]
  (testing (str "testing normalized cache reading for fragment type: " k)
    (let [{:keys [fragment entity entities read-result variables]} (get test-fragments k)
          store (create-store :entities entities
                              :cache-key ::cache)
          response (a/read-fragment store fragment entity :variables variables)]
      (is (= {:data read-result
              :partial? false} response)))))

(deftest test-cache-fragment-reading
  (doseq [test-fragment (keys test-fragments)]
    (read-fragment-test test-fragment)))

(deftest return-partial
  (let [entities {[:artemis.mapgraph/generated "root"]
                  {:object1 {:artemis.mapgraph/ref "aa"}
                   ::cache  [:artemis.mapgraph/generated "root"]}
                  "aa"
                  {:id             "aa"
                   :stringField    "this is a string"
                   :otherObject    {:artemis.mapgraph/ref "bb"}
                   :otherObjects   [{:artemis.mapgraph/ref "aaa"}
                                    {:artemis.mapgraph/ref "bbb"}]
                   :missingObjects [{:artemis.mapgraph/ref "aaa"}
                                    {:artemis.mapgraph/ref "ccc"}]}
                  "bb"
                  {:id "bb"}
                  "aaa"
                  {:id          "aaa"
                   :stringField "aaa's string"}
                  "bbb"
                  {:id "bbb"}}
        store (create-store :entities entities
                            :cache-key ::cache)]
    (testing "query return-partial"
      (testing "set to true and all data not present"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          numberField
                                        }
                                      }")]
          (is (= (:data (a/read store query {} :return-partial? true))
                 {:object1
                  {:id          "aa"
                   :stringField "this is a string"}}))))
      (testing "set to true and all data in a obj within a nested array not present"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          otherObjects {
                                            id
                                            stringField
                                          }
                                        }
                                      }")]
          (is (= (:data (a/read store query {} :return-partial? true))
                 {:object1
                  {:id           "aa"
                   :stringField  "this is a string"
                   :otherObjects [{:id          "aaa"
                                   :stringField "aaa's string"}
                                  {:id "bbb"}]}}))))
      (testing "set to true and an obj within a nested array not present"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          missingObjects {
                                            id
                                          }
                                        }
                                      }")]
          (is (= (:data (a/read store query {} :return-partial? true))
                 {:object1
                  {:id          "aa"
                   :stringField "this is a string"}}))))
      (testing "set to false nested key doesn't exist"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          doesntExist { slug }
                                        }
                                      }")]
          (is (nil? (:data (a/read store query {}))))))
      (testing "set to false all data not present"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          numberField
                                        }
                                      }")]
          (is (nil? (:data (a/read store query {}))))))
      (testing "set to false all data not present deeply"
        (let [query (d/parse-document "{
                                        object1 {
                                          otherObject {
                                            title
                                          }
                                          id
                                        }
                                      }")]
          (is (nil? (:data (a/read store query {}))))))
      (testing "set to false and all data not present in objs within nested array"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          otherObjects {
                                            id
                                            stringField
                                          }
                                        }
                                      }")]
          (is (nil? (:data (a/read store query {}))))))
      (testing "set to false and all data not present within nested array"
        (let [query (d/parse-document "{
                                        object1 {
                                          id
                                          stringField
                                          missingObjects {
                                            id
                                          }
                                        }
                                      }")]
          (is (nil? (:data (a/read store query {})))))))
    (testing "fragment return-partial"
      (testing "set to true and all data not present"
        (let [fragment (d/parse-document "fragment A on object {
                                           id
                                           stringField
                                           numberField
                                         }")]
          (is (= (:data (a/read-fragment store fragment "aa" :return-partial? true))
                 {:id          "aa"
                  :stringField "this is a string"}))))
      (testing "set to true and all data not present"
        (let [fragment (d/parse-document "fragment A on object {
                                           id
                                           stringField
                                           numberField
                                         }")]
          (is (nil? (:data (a/read-fragment store fragment "aa")))))))))

(deftest empty-store-test
  (let [empty-store (create-store :entities {}
                                  :cache-key ::cache)]
    (testing "return partial set to true with an empty store"
      (let [query (d/parse-document "{ nonExistentField }")]
        (is (nil? (:data (a/read empty-store query {} :return-partial? true))))))
    (testing "return partial set to galse with an empty store"
      (let [query (d/parse-document "{ nonExistentField }")]
        (is (nil? (:data (a/read empty-store query {} :return-partial? false))))))))

(def cache-redirects-map
  {:book (comp :id :variables)
   :featuredBook (comp #(when (= % "bob") 1) :slug :variables)})

(deftest cache-redirects
  (let [entities {[:artemis.mapgraph/generated "root"]
                        {::cache                      [:artemis.mapgraph/generated "root"]
                         "author({\"slug\":\"bob\"})" {:artemis.mapgraph/ref "bob"}
                         "books"                      [{:artemis.mapgraph/ref 1} {:artemis.mapgraph/ref 2}]}

                  "bob" {:slug "bob"}

                  1     {:id 1 :title "Book 1", :abstract "Lorem ipsum..."}
                  2     {:id 2 :title "Book 2", :abstract "Lorem ipsum..."}}

        store (create-store :entities entities
                            :cache-redirects cache-redirects-map
                            :cache-key ::cache)]
    (testing "simple redirect"
      (let [query (d/parse-document "{
                                      book(id: $id) {
                                        id
                                        title
                                        abstract
                                      }
                                    }")]
        (is (= (:data (a/read store query {:id 1}))
               {:book {:id 1
                       :title "Book 1"
                       :abstract "Lorem ipsum..."}}))
        (is (= (:data (a/read store query {:id 2}))
               {:book {:id 2
                       :title "Book 2"
                       :abstract "Lorem ipsum..."}}))
        (is (= (:data (a/read store query {:id 3}))
               nil))))
    (testing "deep redirect"
      (let [query (d/parse-document "{
                                       author(slug: $slug) {
                                         featuredBook {
                                           title
                                         }
                                       }
                                     }")]
        (is (= (:data (a/read store query {:slug "bob"}))
               {:author {:featuredBook {:title "Book 1"}}}))))
    (testing "mixed with non-redirect fields"
      (let [query (d/parse-document "{
                                       author(slug: $slug) {
                                         slug
                                         featuredBook {
                                           title
                                         }
                                       }
                                     }")]
        (is (= (:data (a/read store query {:slug "bob"}))
               {:author {:slug         "bob"
                         :featuredBook {:title "Book 1"}}}))))))

(deftest path-warning
  (let [log (atom [])]
    (with-redefs [warn (fn [s] (swap! log conj s))]
      (testing "warns when result containing a non normalized entity will overwrite a normalized entity"
        (let [entities {[:artemis.mapgraph/generated "root"]
                              {::cache                    [:artemis.mapgraph/generated "root"]
                               "author({\"id\":\"bob\"})" {:artemis.mapgraph/ref "bob"}}
                        "bob" {:id "bob"}}
              store (create-store :entities entities
                                  :cache-redirects cache-redirects-map
                                  :cache-key ::cache)
              query (d/parse-document "query GetAuthor1 {
                                         author(id: \"bob\") {
                                           name
                                         }
                                         ignore
                                       }")
              result {:author {:name "Bob Dylan"}
                      :ignore "should be ignored"}
              updated-store (a/write store {:data result} query {})]
          (is (= (first @log)
                 "query:GetAuthor1 | New result at key `author({\"id\":\"bob\"})` under `[:artemis.mapgraph/generated \"root\"]` likely to overwrite data"))))
      (testing "warns when result containing a normalized entity will overwrite a non normalized entity"
        (let [entities {[:artemis.mapgraph/generated "root"]
                        {::cache                    [:artemis.mapgraph/generated "root"]
                         "author({\"id\":\"bob\"})" {:artemis.mapgraph/ref [:artemis.mapgraph/generated "root.author({\"id\":\"bob\"})"]}}
                        [:artemis.mapgraph/generated "root.author({\"id\":\"bob\"})"]
                        {:name "Bob Dylan"}}
              store (create-store :entities entities
                                  :cache-redirects cache-redirects-map
                                  :cache-key ::cache)
              query (d/parse-document "query GetAuthor2 {
                                         author(id: \"bob\") {
                                           id
                                         }
                                         ignore
                                       }")
              result {:author {:id "bob"}
                      :ignore "should be ignored"}
              updated-store (a/write store {:data result} query {})]
          (is (= (second @log)
                 "query:GetAuthor2 | New result at key `author({\"id\":\"bob\"})` under `[:artemis.mapgraph/generated \"root\"]` likely to overwrite data"))))
      (testing "don't warn for these other cases"
        (let [entities {[:artemis.mapgraph/generated "root"]
                              {::cache                    [:artemis.mapgraph/generated "root"]
                               "author({\"id\":\"bob\"})" {:artemis.mapgraph/ref "bob"}}
                        "bob" {:id "bob"}}
              store (create-store :entities entities
                                  :cache-redirects cache-redirects-map
                                  :cache-key ::cache)
              bob-query (d/parse-document "{
                                             author(id: \"bob\") {
                                               id
                                               name
                                             }
                                             ignore
                                           }")
              bill-query (d/parse-document "{
                                              author(id: \"bill\") {
                                                name
                                              }
                                              ignore
                                            }")
              bob-result {:author {:name "Bob Dylan" :id "bob"}
                          :ignore "should be ignored"}
              bill-result {:author {:name "Bill Hicks" :id "bill"}
                           :ignore "should be ignored"}
              bill-result-no-id {:author {:name "Bill Hicks"}
                                 :ignore "should be ignored"}
              ref-query (d/parse-document "{ key { id ref { int } } }")
              ref-query-different-key (d/parse-document "{ differentKey { id ref { int } } }")
              _ (-> store
                    (a/write {:data bob-result} bob-query {})
                    (a/write {:data bill-result} bob-query {})
                    (a/write {:data bill-result-no-id} bill-query {})
                    (a/write {:data {:key {:id 88 :ref {:int 11}}}} ref-query {})
                    (a/write {:data {:differentKey {:id 88 :ref {:int 11}}}} ref-query-different-key {}))]
          (is (= 2 (count @log))))))))
