(ns artemis.network-steps.http
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs-http.client :as http]
            [artemis.result :as ar]
            [artemis.network-steps.protocols :as np]
            [cljs.core.async :as async]))

(defn- post-operation [this operation {:keys [interchange-format]
                                       :or   {interchange-format :json}
                                       :as   context}]
  (let [[params accept] (case interchange-format
                          :json [:json-params "application/json"]
                          :edn  [:edn-params "application/edn"]
                          (throw (ex-info (str "Invalid data interchange format. "
                                               "Must be one of #{:json :edn}.")
                                          {:value  interchange-format})))]
    (http/post
      (:uri this)
      (-> context
          (select-keys [:with-credentials? :oauth-token :basic-auth :headers :query-params])
          (merge {:accept accept
                  params  (:graphql operation)})))))

(defrecord
  ^{:added "0.1.0"}
  HttpNetworkStep
  [uri opts]
  np/GQLNetworkStep
  (-exec [this operation context]
    (let [next (fn [data errors meta]
                 (let [on-response (some-> this :opts :on-response)]
                   (when (fn? on-response)
                     (on-response data errors meta))
                   (ar/with-errors data errors)))
          meta {:operation operation
                :context   context}]
      (try
        (let [c (post-operation this operation context)]
          (go (let [{:keys [body error-code] :as res} (async/<! c)
                    net-error (when (not= error-code :no-error)
                                {:message  (str "Network error " error-code)
                                 :response res})
                    unpack    (:unpack operation)
                    data      (unpack (:data body))
                    errors    (into (:errors body) net-error)]
                (next {:data data} errors (merge meta {:response res})))))
        (catch :default e
          (async/to-chan
           [(next
             {:data nil}
             [(assoc (ex-data e) :message (.-message e))]
             meta)]))))))

;; Public API
(defn create-network-step
  "Returns a new `HttpNetworkStep` for a given uri. The uri defaults to
  `\"/graphql\"`. Makes requests via cljs-http."
  {:added "0.1.0"}
  ([]
   (create-network-step "/graphql"))
  ([uri]
   (create-network-step uri {}))
  ([uri opts]
   (HttpNetworkStep. uri opts)))
