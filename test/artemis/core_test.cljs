(ns artemis.core-test
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [cljs.core.async :refer [chan]]
            [artemis.core :as core]
            [artemis.test-util :as tu]))

(deftest create-client
  (is (some? (core/create-client)))
  (is (core/client? (core/create-client))))

(deftest get-store-and-network-chain
  (let [s      (tu/stub-store {})
        n      (tu/stub-net-chain (chan))
        client (core/create-client s n)]
    (is (= s (core/store client)))
    (is (= n (core/network-chain client)))))

(deftest client?
  (is (true? (core/client? (core/create-client))))
  (is (false? (core/client? {})))
  (is (false? (core/client? {:store {} :network-chain {}}))))
