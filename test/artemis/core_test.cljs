(ns artemis.core-test
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.core.async :refer [chan <! put!]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test.check.generators]
            [artemis.core :as core]
            [artemis.document :refer [parse-document]]
            [artemis.result :refer [with-errors]]
            [artemis.stores.protocols :as sp]
            [artemis.network-steps.protocols :as np]))

(defn- stub-net-chain [mock-chan]
  (reify np/GQLNetworkStep
    (-exec [_ _ _] mock-chan)))

(defn- stub-store [c]
  (reify sp/GQLStore
    (-query [_ doc variables _]
      (let [result (get-in c [:heros (:episode variables)])]
        (cond-> {:data result}
          (nil? result) (with-errors [{:message "Couldn't find hero"}]))))))

(deftest create-client
  (is (some? (core/create-client)))
  (is (core/client? (core/create-client))))

(deftest get-store-and-network-chain
  (let [s      (stub-store {})
        n      (stub-net-chain (chan))
        client (core/create-client s n)]
    (is (= s (core/store client)))
    (is (= n (core/network-chain client)))))

(deftest client?
  (let [client (core/create-client)]
    (is (true? (core/client? client)))
    (is (false? (core/client? {})))
    (is (false? (core/client? {:store {} :network-chain {}})))))

;; -------------------------
;; artemis.core/query tests
;; -------------------------

(defonce mock-cache   {:heros {"The Empire Strikes Back" "Luke Skywalker"}})
(defonce mock-chan    (chan))
(defonce client       (core/create-client (stub-store mock-cache) (stub-net-chain mock-chan)))
(defonce doc          (parse-document "query Hero($episode: String!) { hero(episode: $episode) { name } }"))
(defonce variables    {:episode "The Empire Strikes Back"})
(defonce query        (partial core/query client doc variables :fetch-policy))
(defonce uc-variables {:episode "A New Hope"})
(defonce uc-query     (partial core/query client doc uc-variables :fetch-policy))

(deftest query-local-only-cached
  (testing "local-only when data available"
    (async done
      (go (let [local-chan (query :local-only)
                result     (<! local-chan)
                closed?    (nil? (<! local-chan))]
            (is (= (:data result) "Luke Skywalker"))
            (is (= (:variables result) variables))
            (is (= (:source result) :local))
            (is (= (:network-status result) :ready))
            (is (false? (:in-flight? result)))
            (is closed?)
            (done))))))

(deftest query-local-only-not-cached
  (testing "local-only when data not available"
    (async done
      (go (let [local-chan (uc-query :local-only)
                result     (<! local-chan)
                closed?    (nil? (<! local-chan))]
            (is (nil? (:data result)))
            (is (= 1 (count (:errors result))))
            (is (= (:variables result) uc-variables))
            (is (= (:source result) :local))
            (is (= (:network-status result) :ready))
            (is (false? (:in-flight? result)))
            (is closed?)
            (done))))))

(deftest query-local-first-cached
  (testing "local-first when data available"
    (async done
      (go (let [local-chan (query :local-first)
                result     (<! local-chan)
                closed?    (nil? (<! local-chan))]
            (is (= (:data result) "Luke Skywalker"))
            (is (nil? (:errors result)))
            (is (= (:variables result) variables))
            (is (= (:source result) :local))
            (is (= (:network-status result) :ready))
            (is (false? (:in-flight? result)))
            (is closed?)
            (done))))))

(deftest query-local-first-not-cached
  (testing "local-first when no data available"
    (async done
      (let [result-chan (uc-query :local-first)]
        (go (let [local-result  (<! result-chan)
                  remote-result (<! result-chan)
                  closed?       (nil? (<! result-chan))]
              (is (nil? (:data local-result)))
              (is (= (:variables local-result) uc-variables))
              (is (= (:source local-result) :local))
              (is (= (:network-status local-result) :fetching))
              (is (true? (:in-flight? local-result)))

              (is (= (:data remote-result) "Luke Skywalker"))
              (is (= (:variables remote-result) uc-variables))
              (is (= (:source remote-result) :remote))
              (is (= (:network-status remote-result) :ready))
              (is (false? (:in-flight? remote-result)))
              (is closed?)
              (done)))
        (put! mock-chan {:data "Luke Skywalker"})))))

(deftest query-local-then-remote-cached
  (testing "local-then-remote when data available"
    (async done
      (let [result-chan (query :local-then-remote)]
        (go (let [local-result  (<! result-chan)
                  remote-result (<! result-chan)
                  closed?       (nil? (<! result-chan))]
              (is (= (:data local-result) "Luke Skywalker"))
              (is (= (:variables local-result) variables))
              (is (= (:source local-result) :local))
              (is (= (:network-status local-result) :fetching))
              (is (true? (:in-flight? local-result)))

              (is (= (:data remote-result) "Luke Skywalker"))
              (is (= (:variables remote-result) variables))
              (is (= (:source remote-result) :remote))
              (is (= (:network-status remote-result) :ready))
              (is (false? (:in-flight? remote-result)))
              (is closed?)
              (done)))
        (put! mock-chan {:data "Luke Skywalker"})))))

(deftest query-local-then-remote-not-cached
  (testing "local-then-remote when data not available"
    (async done
      (let [result-chan (uc-query :local-then-remote)]
        (go (let [local-result  (<! result-chan)
                  remote-result (<! result-chan)
                  closed?       (nil? (<! result-chan))]
              (is (nil? (:data local-result)))
              (is (= (:variables local-result) uc-variables))
              (is (= (:source local-result) :local))
              (is (= (:network-status local-result) :fetching))
              (is (true? (:in-flight? local-result)))

              (is (= (:data remote-result) "Luke Skywalker"))
              (is (= (:variables remote-result) uc-variables))
              (is (= (:source remote-result) :remote))
              (is (= (:network-status remote-result) :ready))
              (is (false? (:in-flight? remote-result)))
              (is closed?)
              (done)))
        (put! mock-chan {:data "Luke Skywalker"})))))

(deftest query-remote-only
  (async done
    (let [result-chan (query :remote-only)]
      (go (let [local-result  (<! result-chan)
                remote-result (<! result-chan)
                closed?       (nil? (<! result-chan))]
            (is (nil? (:data local-result)))
            (is (= (:variables local-result) variables))
            (is (= (:source local-result) :local))
            (is (= (:network-status local-result) :fetching))
            (is (true? (:in-flight? local-result)))

            (is (= (:data remote-result) "Luke Skywalker"))
            (is (= (:variables remote-result) variables))
            (is (= (:source remote-result) :remote))
            (is (= (:network-status remote-result) :ready))
            (is (false? (:in-flight? remote-result)))
            (is closed?)
            (done)))
      (put! mock-chan {:data "Luke Skywalker"}))))

(deftest query-invalid
  (is (thrown-with-msg? ExceptionInfo #"Invalid :fetch-policy"
                        (query :something-else))))
