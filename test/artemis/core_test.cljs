(ns artemis.core-test
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.core.async :refer [chan <!]]
            [artemis.core :as core]
            [artemis.test-util :as tu :refer [with-client]]))

(deftest create-client
  (is (some? (core/create-client)))
  (is (core/client? (core/create-client))))

(deftest get-store-and-network-chain
  (let [s      (tu/stub-store {})
        n      (tu/stub-net-chain (chan))
        client (core/create-client :store s :network-chain n)]
    (is (= s (core/store client)))
    (is (= n (core/network-chain client)))))

(deftest client?
  (is (true? (core/client? (core/create-client))))
  (is (false? (core/client? {})))
  (is (false? (core/client? {:store {} :network-chain {}}))))

(deftest watch-store
  (async done
         (let [counter     (atom 0)
               store-write (fn [this _ _ _] this)
               variables   {:episode "The Empire Strikes Back"
                            :reivew  {:stars      4
                                      :commentary "This is a great movie!"}}
               data        {:data
                            {:createReview
                             {:stars 4 :commentary "This is a great movie!"}}}]
           (with-client
             {:store-write-fn store-write}
             (let [unwatch     (core/watch-store client #(swap! counter inc))
                   result-chan (core/mutate! client tu/mutation-doc variables)]
               (go
                 (testing "calls the callback"
                   (let [_ (<! result-chan) ; first local result
                         _ (<! result-chan) ; first remote result
                         ]
                     (is (= @counter 1)))
                   (let [_ (<! result-chan) ; second local result
                         _ (<! result-chan) ; second remote result
                         ]
                     (is (= @counter 2))))
                 (testing "unwatch"
                   (let [_ (unwatch)
                         _ (<! result-chan) ; third local result
                         _ (<! result-chan) ; third remote result
                         ]
                     (is (= @counter 1))))))))
         (done)))
