(ns artemis.stores.normalized-in-memory-store-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [artemis.stores.normalized-in-memory-store :as mem-store]))

(deftest store?
  (is (true? (mem-store/store? (mem-store/create-store))))
  (is (false? (mem-store/store? {})))
  (is (false? (mem-store/store? (assoc (mem-store/create-store) :entities '()))))
  (is (false? (mem-store/store? (assoc (mem-store/create-store) :unique-indexes '())))))

(deftest add-unique-index
  (let [s (mem-store/create-store #{:id})]
    (is (= (-> (mem-store/add-unique-index s :slug)
               :unique-indexes)
           #{:id :slug}))))

(deftest add
  (let [s (mem-store/create-store #{:id})
        p {:id 1 :name "Alice Smith"}]
    (testing "adds and normalizes entities"
      (is (= (-> s
                 (mem-store/add p)
                 :entities
                 (get [:id 1]))
             p))
      (is (= (-> s
                 (mem-store/add (assoc p :email "alice@smith.co"))
                 :entities
                 (get [:id 1]))
             (assoc p :email "alice@smith.co"))))
    (testing "merges existing entities"
      (is (= (-> s
                 (mem-store/add (assoc p :name "Aliss Smith"))
                 (mem-store/add {:id 1 :email "aliss@smith.co"})
                 :entities
                 (get [:id 1]))
             (assoc p :name "Aliss Smith" :email "aliss@smith.co"))))))

(deftest clear
  (let [s (-> (mem-store/create-store #{:id})
              (mem-store/add {:id 1 :name "Alice Smith"}))]
    (is (true? (mem-store/store? s)))
    (is (empty? (:entities (mem-store/clear s))))
    (is (empty? (:unique-indexes (mem-store/clear s))))))

(deftest entity?
  (let [s (mem-store/create-store #{:id})]
    (is (true? (mem-store/entity? s {:id 1})))
    (is (false? (mem-store/entity? s {:slug "xyz"})))))

(deftest ref-to
  (let [s (mem-store/create-store #{:id})]
    (is (= (mem-store/ref-to s {:id 1})
           [:id 1]))
    (is (nil? (mem-store/ref-to s {:slug "xyz"})))))

(deftest ref?
  (let [s (mem-store/create-store #{:id})]
    (is (true? (mem-store/ref? s [:id 1])))
    (is (false? (mem-store/ref? s [:id])))
    (is (false? (mem-store/ref? s [:slug "xyz"])))))
