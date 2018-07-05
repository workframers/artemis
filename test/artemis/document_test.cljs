(ns artemis.document-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [cljs.core.async :refer [chan]]
            [artemis.document :as d]
            [artemis.test-util :as tu]))

(deftest parse-document
  (let [doc (d/parse-document "{ a b c }")]
    (is (some? doc))))

(deftest operation
  (let [doc (d/parse-document "{ a b c }")
        op  (d/operation doc)]
    (is (some? op))
    (is (string? (:query (:graphql op))))
    (is (map? (:variables (:graphql op))))
    (is (fn? (:unpack op)))))

(deftest compose
  (testing "composing two queries"
    (let [doc-a (d/parse-document "query QueryA { a b c }")
          doc-b (d/parse-document "query QueryB { d e f }")
          doc-c (d/compose doc-a doc-b)]
      (is (some? doc-c))
      (is (= 2 (count (:operation-definitions doc-c))))
      (is (= {:type "query", :name "QueryA"}
             (:operation-type (first (:operation-definitions doc-c)))))
      (is (= {:type "query", :name "QueryB"}
             (:operation-type (second (:operation-definitions doc-c)))))))

  (testing "composing a query and a fragment"
    (let [doc-a (d/parse-document "query QueryA { a b c ...Fields }")
          doc-b (d/parse-document "fragment Fields on Thing { d e f }")
          doc-c (d/compose doc-a doc-b)]
      (is (some? doc-c))
      (is (= 1 (count (:operation-definitions doc-c))))
      (is (= 1 (count (:fragment-definitions doc-c))))))

  (testing "composing a query and a multiple fragments"
    (let [doc-a (d/parse-document "query QueryA { a b c ...Fields }")
          doc-b (d/parse-document "fragment Fields on Thing { d e f ...MoreFields }")
          doc-c (d/parse-document "fragment MoreFields on Thing { g h i }")
          doc-d (d/compose doc-a doc-b doc-c)]
      (is (some? doc-d))
      (is (= 1 (count (:operation-definitions doc-d))))
      (is (= 2 (count (:fragment-definitions doc-d)))))))

(deftest inline-fragments
  (testing "inlining a single fragment"
    (let [doc (d/parse-document "query SomeQuery {
                                   a
                                   ...Fields
                                 }

                                fragment Fields on Thing {
                                  b
                                  c
                                }")]
      (is (= {:operation-definitions
              [{:section        :operation-definitions
                :node-type      :operation-definition
                :operation-type {:type "query"
                                 :name "SomeQuery"}
                :selection-set  [{:node-type  :field
                                  :field-name "a"}
                                 {:node-type  :field
                                  :field-name "__typename"}
                                 {:node-type  :field
                                  :field-name "b"}
                                 {:node-type  :field
                                  :field-name "c"}]}]}
             (d/inline-fragments doc)))))

  (testing "inlining nested fragments"
    (let [doc (d/parse-document "query SomeQuery {
                                   a
                                   ...Fields
                                 }

                                fragment Fields on Thing { b ...MoreFields }
                                fragment MoreFields on Thing { c }")]
      (is (= {:operation-definitions
              [{:section        :operation-definitions
                :node-type      :operation-definition
                :operation-type {:type "query"
                                 :name "SomeQuery"}
                :selection-set  [{:node-type  :field
                                  :field-name "a"}
                                 {:node-type  :field
                                  :field-name "b"}
                                 {:node-type  :field
                                  :field-name "__typename"}
                                 {:node-type  :field
                                  :field-name "c"}]}]}
             (d/inline-fragments doc))))))

(deftest select
  (testing "a single query"
    (let [doc  (d/parse-document
                "{
                   alias: name
                   height(unit: METERS)
                   avatar {
                     square
                   }
                 }")
          data {:alias  "Bob"
                :name   "Wrong"
                :height 1.89
                :avatar {:square   "abc"
                         :circle   "def"
                         :triangle "ghi"}}]
      (is (= (d/select doc data true)
             {:alias  "Bob"
              :height 1.89
              :avatar {:square "abc"}}))))

  (testing "a single fragment"
    (let [doc  (d/parse-document
                "fragment PersonDetails on Person {
                   alias: name
                   height(unit: METERS)
                   avatar {
                     square
                   }
                 }")
          data {:alias  "Bob"
                :name   "Wrong"
                :height 1.89
                :avatar {:square   "abc"
                         :circle   "def"
                         :triangle "ghi"}}]
      (is (= (d/select doc data true)
             {:alias  "Bob"
              :height 1.89
              :avatar {:square "abc"}}))))

  (testing "nested fragments"
    (let [doc  (d/parse-document
                "fragment PersonDetails on Person {
                   alias: name
                   height(unit: METERS)
                   avatar {
                     square
                     ... on Avatar {
                       circle
                     }
                   }
                 }")
          data {:alias  "Bob"
                :name   "Wrong"
                :height 1.89
                :avatar {:square   "abc"
                         :circle   "def"
                         :triangle "ghi"}}]
      (is (= (d/select doc data true)
             {:alias  "Bob"
              :height 1.89
              :avatar {:square "abc"
                       :circle "def"}}))))

  (testing "multiple fragments"
    (let [doc  (d/parse-document
                "fragment PersonDetails on Person {
                   alias: name
                   height(unit: METERS)
                   ...Avatars
                 }

                 fragment Avatars on Person {
                  avatar {
                    square
                    circle
                  }
                 }
                ")
          data {:alias  "Bob"
                :name   "Wrong"
                :height 1.89
                :avatar {:square   "abc"
                         :circle   "def"
                         :triangle "ghi"}}]
      (is (= (d/select doc data true)
             {:alias  "Bob"
              :height 1.89
              :avatar {:square "abc"
                       :circle "def"}}))))

  (testing "unmatched values are nil or dropped with flag"
    (let [doc  (d/parse-document
                "{
                   alias: name
                   email
                 }")
          data {:alias  "Bob"
                :name   "Wrong"
                :height 1.89
                :avatar {:square   "abc"
                         :circle   "def"
                         :triangle "ghi"}}]
      (is (= (d/select doc data)
             {:__typename nil
              :alias      "Bob"
              :email      nil}))
      (is (= (d/select doc data true)
             {:alias "Bob"})))))
