(ns artemis.core-query-test
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.core.async :refer [chan <! put!]]
            [artemis.core :as core]
            [artemis.test-util :as tu :refer [with-client]]))

(def variables {:episode "The Empire Strikes Back"})

(deftest query-default-fetch-policy
  (testing "respects the configured default fetch policy"
    (async done
      (let [cache (atom {})]
        (with-client
          {:defaults       {:fetch-policy :remote-only}
           :store-query-fn (fn [_ _ {:keys [episode]} _]
                             {:data (get @cache episode)})
           :store-write-fn (fn [this {:keys [data]} _ {:keys [episode]}]
                             (reset! cache {episode data})
                             this)}

          ;; Default config is set
          (is (= (get-in client [:defaults :fetch-policy]) :remote-only))

          ;; No fetch-policy set, but expect a remote only query
          (let [result-chan (core/query! client tu/query-doc variables)]
            (go (let [local-result  (<! result-chan)]
                  (is (nil? (:data local-result)))
                  (is (true? (:in-flight? local-result))))

                ;; Putting a fake remote result
                (put-result! {:data "Luke Skywalker"})

                (let [remote-result (<! result-chan)
                      closed?       (nil? (<! result-chan))]
                  (is (= (:data remote-result) "Luke Skywalker"))
                  (is (false? (:in-flight? remote-result)))
                  (is closed?)
                  (done)))))))))

(deftest query-no-cache
  (async done
    (let [cache (atom {})]
      (with-client
       {:store-query-fn (fn [_ _ {:keys [episode]} _]
                          {:data (get @cache episode)})
        :store-write-fn (fn [this {:keys [data]} _ {:keys [episode]}]
                          (reset! cache {episode data})
                          this)}
       (let [result-chan (core/query! client tu/query-doc variables :fetch-policy :no-cache)]
         (go (let [local-result  (<! result-chan)]
               (is (nil? (:data local-result)))
               (is (= (:variables local-result) variables))
               (is (= (:network-status local-result) :fetching))
               (is (true? (:in-flight? local-result)))
               (is (= @cache {})))

             ;; Putting a fake remote result
             (put-result! {:data "Luke Skywalker"})

             (let [remote-result (<! result-chan)
                   closed?       (nil? (<! result-chan))]
               (is (= (:data remote-result) "Luke Skywalker"))
               (is (= (:variables remote-result) variables))
               (is (= (:network-status remote-result) :ready))
               (is (false? (:in-flight? remote-result)))
               (is (= @cache {}))
               (is closed?)
               (done))))))))

(deftest query-local-only-cached
  (testing "local-only when data available"
    (async done
      (with-client
       {:store-query-fn (constantly {:data "Luke Skywalker"})}
       (go (let [local-chan (core/query! client tu/query-doc variables :fetch-policy :local-only)
                 result     (<! local-chan)
                 closed?    (nil? (<! local-chan))]
             (is (= (:data result) "Luke Skywalker"))
             (is (= (:variables result) variables))
             (is (= (:network-status result) :ready))
             (is (false? (:in-flight? result)))
             (is closed?)
             (done)))))))

(deftest query-local-only-not-cached
  (testing "local-only when data not available"
    (async done
      (with-client
       {:store-query-fn (constantly {:data nil :errors '({:message "Couldn't find hero."})})}
       (go (let [local-chan (core/query! client tu/query-doc variables :fetch-policy :local-only)
                 result     (<! local-chan)
                 closed?    (nil? (<! local-chan))]
             (is (nil? (:data result)))
             (is (= 1 (count (:errors result))))
             (is (= (:variables result) variables))
             (is (= (:network-status result) :ready))
             (is (false? (:in-flight? result)))
             (is closed?)
             (done)))))))

(deftest query-local-first-cached
  (testing "local-first when data available"
    (async done
      (with-client
       {:store-query-fn (constantly {:data "Luke Skywalker"})}
       (go (let [local-chan (core/query! client tu/query-doc variables :fetch-policy :local-first)
                 result     (<! local-chan)
                 closed?    (nil? (<! local-chan))]
             (is (= (:data result) "Luke Skywalker"))
             (is (nil? (:errors result)))
             (is (= (:variables result) variables))
             (is (= (:network-status result) :ready))
             (is (false? (:in-flight? result)))
             (is closed?)
             (done)))))))

(deftest query-local-first-not-cached
  (testing "local-first when no data available"
    (async done
      (let [cache (atom {})]
        (with-client
         {:store-query-fn (fn [_ _ {:keys [episode]} _]
                            {:data (get @cache episode)})
          :store-write-fn (fn [this {:keys [data]} _ {:keys [episode]}]
                            (reset! cache {episode data})
                            this)}
         (let [result-chan (core/query! client tu/query-doc variables :fetch-policy :local-first)]
           (go (let [local-result (<! result-chan)]
                 (is (nil? (:data local-result)))
                 (is (= (:variables local-result) variables))
                 (is (= (:network-status local-result) :fetching))
                 (is (true? (:in-flight? local-result))))

               ;; Putting a fake remote result
               (put-result! {:data "Luke Skywalker"})

               (let [remote-result (<! result-chan)
                     closed?       (nil? (<! result-chan))]
                 (is (= (:data remote-result) "Luke Skywalker"))
                 (is (= (:variables remote-result) variables))
                 (is (= (:network-status remote-result) :ready))
                 (is (false? (:in-flight? remote-result)))
                 (is (= @cache {"The Empire Strikes Back" "Luke Skywalker"}))
                 (is closed?)
                 (done)))))))))

(deftest query-local-then-remote-cached
  (testing "local-then-remote when data available"
    (async done
      (let [cache (atom {"The Empire Strikes Back" "R2D2"})]
        (with-client
         {:store-query-fn (fn [_ _ {:keys [episode]} _]
                            {:data (get @cache episode)})
          :store-write-fn (fn [this {:keys [data]} _ {:keys [episode]}]
                            (reset! cache {episode data})
                            this)}
         (let [result-chan (core/query! client tu/query-doc variables :fetch-policy :local-then-remote)]
           (go (let [local-result (<! result-chan)]
                 (is (= (:data local-result) "R2D2"))
                 (is (= (:variables local-result) variables))
                 (is (= (:network-status local-result) :fetching))
                 (is (true? (:in-flight? local-result))))

               ;; Putting a fake remote result
               (put-result! {:data "Luke Skywalker"})

               (let [remote-result (<! result-chan)
                     closed?       (nil? (<! result-chan))]
                 (is (= (:data remote-result) "Luke Skywalker"))
                 (is (= (:variables remote-result) variables))
                 (is (= (:network-status remote-result) :ready))
                 (is (false? (:in-flight? remote-result)))
                 (is (= @cache {"The Empire Strikes Back" "Luke Skywalker"}))
                 (is closed?)
                 (done)))))))))

(deftest query-local-then-remote-not-cached
  (testing "local-then-remote when data not available"
    (async done
      (let [cache (atom {})]
        (with-client
         {:store-query-fn (fn [_ _ {:keys [episode]} _]
                            {:data (get @cache episode)})
          :store-write-fn (fn [this {:keys [data]} _ {:keys [episode]}]
                            (reset! cache {episode data})
                            this)}
         (let [result-chan (core/query! client tu/query-doc variables :fetch-policy :local-then-remote)]
           (go (let [local-result (<! result-chan)]
                 (is (nil? (:data local-result)))
                 (is (= (:variables local-result) variables))
                 (is (= (:network-status local-result) :fetching))
                 (is (true? (:in-flight? local-result))))

               ;; Putting a fake remote result
               (put-result! {:data "Luke Skywalker"})

               (let [remote-result (<! result-chan)
                     closed?       (nil? (<! result-chan))]
                 (is (= (:data remote-result) "Luke Skywalker"))
                 (is (= (:variables remote-result) variables))
                 (is (= (:network-status remote-result) :ready))
                 (is (false? (:in-flight? remote-result)))
                 (is (= @cache {"The Empire Strikes Back" "Luke Skywalker"}))
                 (is closed?)
                 (done)))))))))

(deftest query-remote-only
  (async done
    (let [cache (atom {})]
      (with-client
       {:store-query-fn (fn [_ _ {:keys [episode]} _]
                          {:data (get @cache episode)})
        :store-write-fn (fn [this {:keys [data]} _ {:keys [episode]}]
                          (reset! cache {episode data})
                          this)}
       (let [result-chan (core/query! client tu/query-doc variables :fetch-policy :remote-only)]
         (go (let [local-result  (<! result-chan)]
               (is (nil? (:data local-result)))
               (is (= (:variables local-result) variables))
               (is (= (:network-status local-result) :fetching))
               (is (true? (:in-flight? local-result)))
               (is (= @cache {})))

             ;; Putting a fake remote result
             (put-result! {:data "Luke Skywalker"})

             (let [remote-result (<! result-chan)
                   closed?       (nil? (<! result-chan))]
               (is (= (:data remote-result) "Luke Skywalker"))
               (is (= (:variables remote-result) variables))
               (is (= (:network-status remote-result) :ready))
               (is (false? (:in-flight? remote-result)))
               (is (= @cache {"The Empire Strikes Back" "Luke Skywalker"}))
               (is closed?)
               (done))))))))

(deftest query-invalid
  (with-client
   (is (thrown? ExceptionInfo
                (core/query! client tu/query-doc :fetch-policy :something-else)))))
