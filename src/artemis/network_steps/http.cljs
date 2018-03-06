(ns artemis.network-steps.http
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs-http.client :as http]
            [artemis.document :as d]
            [artemis.result :as ar]
            [artemis.network-steps.protocols :as np]
            [cljs.core.async :as async]))

(defn- payload [{:keys [document variables]}]
  {:query     (d/source document)
   :variables variables})

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
      (:url this)
      (-> context
          (select-keys [:with-credentials? :oauth-token :basic-auth :headers])
          (merge {:accept accept
                  params  (payload operation)})))))

(defrecord HttpNetworkStep [url]
  np/GQLNetworkStep
  (-exec [this operation context]
    (try
      (let [c (post-operation this operation context)]
        (go (let [{:keys [body error-code] :as res} (async/<! c)
                  net-error   (when (not= error-code :no-error)
                                {:message (str "Network error " error-code)
                                 :response res})
                  data        (:data body)
                  errors      (into (:errors body) net-error)]
              (ar/with-errors {:data data} errors))))
      (catch :default e
        (async/to-chan
         [(ar/with-errors
           {:data nil}
           [(assoc (ex-data e) :message (.-message e))])])))))

;; Public API
(defn create-network-step
  ([]
   (create-network-step "/graphql"))
  ([url]
   (HttpNetworkStep. url)))
