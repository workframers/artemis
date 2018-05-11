(ns artemis.core-subscribe-test
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.core.async :refer [chan <! put!]]
            [artemis.core :as core]
            [artemis.test-util :as tu :refer [with-client]]))

(deftest subscribe
  (async done
    (with-client
     (let [variables   {:some :var}
           result-chan (core/subscribe! client tu/subscription-doc variables)]
       (go-loop [n 0]
         (if (< n 4)
           (when-let [result (<! result-chan)]
             (if (zero? n)
               (do (is (nil? (:data result)))
                   (is (= (:variables result) variables))
                   (is (= (:ws-status result) :connected))
                   (is (= (:ws-message result) :init)))
               (do (is (= (:data result) n))
                   (is (= (:variables result) variables))
                   (is (= (:ws-status result) :connected))
                   (is (= (:ws-message result) :data))))
             (recur (inc n)))
           (done))))

     ;; Putting a few fake sub events
     (put-result! {:data       nil
                   :ws-status  :connected
                   :ws-message :init})
     (doseq [n (range 1 4)]
       (put-result! {:data       n
                     :ws-status  :connected
                     :ws-message :data})))))
