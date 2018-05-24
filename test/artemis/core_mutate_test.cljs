(ns artemis.core-mutate-test
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.core.async :refer [chan <! put!]]
            [artemis.core :as core]
            [artemis.test-util :as tu :refer [with-client]]))

(def variables
  {:episode "The Empire Strikes Back"
   :reivew  {:stars      4
             :commentary "This is a great movie!"}})

(deftest mutate-no-optimistic
  (async done
    (let [counter     (atom 0)
          store-write (fn [this _ _ _] (swap! counter inc) this)]
      (with-client
       {:store-write-fn store-write}
       (let [result-chan (core/mutate! client tu/mutation-doc variables)]
         (go (let [local-result (<! result-chan)]
               (is (nil? (:data local-result)))
               (is (= (:variables local-result) variables))
               (is (= (:network-status local-result) :fetching))
               (is (true? (:in-flight? local-result)))
               (is (zero? @counter)))

             ;; Putting a fake remote result
             (put-result! {:data {:createReview {:stars 4 :commentary "This is a great movie!"}}})

             (let [remote-result (<! result-chan)
                   closed?       (nil? (<! result-chan))]
               (is (= (:data remote-result) {:createReview {:stars 4 :commentary "This is a great movie!"}}))
               (is (= (:variables remote-result) variables))
               (is (= (:network-status remote-result) :ready))
               (is (false? (:in-flight? remote-result)))
               (is (= @counter 1))
               (is closed?)
               (done))))))))

(deftest mutate-optimistic
  (async done
    (let [counter     (atom 0)
          store-write (fn [this _ _ _] (swap! counter inc) this)]
      (with-client
       {:store-write-fn store-write}
       (let [result-chan (core/mutate! client tu/mutation-doc variables
                                      :optimistic-result
                                      {:createReview
                                       {:stars 4
                                        :commentary "This is a great movie!"}})]
         (go (let [local-result (<! result-chan)]
               (is (= (:data local-result) {:createReview {:stars 4 :commentary "This is a great movie!"}}))
               (is (= (:variables local-result) variables))
               (is (= (:network-status local-result) :fetching))
               (is (true? (:in-flight? local-result)))
               (is (= @counter 1)))

             ;; Putting a fake remote result
             (put-result! {:data {:createReview {:stars 4 :commentary "This is a great movie!"}}})

             (let [remote-result (<! result-chan)
                   closed?       (nil? (<! result-chan))]
               (is (= (:data remote-result) {:createReview {:stars 4 :commentary "This is a great movie!"}}))
               (is (= (:variables remote-result) variables))
               (is (= (:network-status remote-result) :ready))
               (is (false? (:in-flight? remote-result)))
               (is (= @counter 2))
               (is closed?)
               (done))))))))

(deftest mutate-hooks-no-optimistic
  (async done
    (let [actions     (atom [])
          hook-fn     (fn [x f] #(do (swap! actions conj x) (f %&)))
          store-write (fn [this _ _ _] (swap! actions conj :write) this)]
      (with-client
       {:store-write-fn store-write}
       (let [result-chan (core/mutate! client tu/mutation-doc variables
                                       :before-write (hook-fn :before second)
                                       :after-write  (hook-fn :after first))]
         (go (let [_ (<! result-chan)]
               (is (empty? @actions)))

             ;; Putting a fake remote result
             (put-result! {:data {:createReview {:stars 4 :commentary "This is a great movie!"}}})

             (let [_ (<! result-chan)]
               (is (= @actions [:before :write :after]))
               (done))))))))

(deftest mutate-hooks-optimistic
  (async done
    (let [actions     (atom [])
          hook-fn     (fn [x f] #(do (swap! actions conj x) (f %&)))
          store-write (fn [this _ _ _] (swap! actions conj :write) this)]
      (with-client
       {:store-write-fn store-write}
       (let [result-chan (core/mutate! client tu/mutation-doc variables
                                       :optimistic-result
                                       {:createReview
                                        {:stars 4
                                        :commentary "This is a great movie!"}}
                                       :before-write (hook-fn :before second)
                                       :after-write  (hook-fn :after first))]
         (go (let [_ (<! result-chan)]
               (is (= @actions [:before :write :after])))

             ;; Putting a fake remote result
             (put-result! {:data {:createReview {:stars 4 :commentary "This is a great movie!"}}})

             (let [_ (<! result-chan)]
               (is (= @actions [:before :write :after :before :write :after]))
               (done))))))))
