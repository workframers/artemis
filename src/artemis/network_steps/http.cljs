(ns artemis.network-steps.http
  (:require [cljs-http.client :as http]
            [artemis.document :as d]
            [artemis.network-steps.protocols :as np]))

(defn- payload [{:keys [document variables]}]
  {:payload
   {:query     (d/source document)
    :variables variables}})

(defrecord HttpNetworkStep [url]
  np/GQLNetworkStep
  (-exec [this operation {:keys [interchange-format]
                          :or   {interchange-format :json}
                          :as   context}]
    (let [[params accept] (case interchange-format
                            :json [:json-params "application/json"]
                            :edn  [:edn-params "application/edn"]
                            (throw (ex-info "Invalid data interchange format.
                                            Must be one of #{:json :edn}."
                                            {:reason ::invalid-interchange-format
                                             :value  interchange-format})))]
      (http/post
       (:url this)
       (-> context
           (select-keys [:with-credentials? :oauth-token :basic-auth :headers])
           (merge {:accept accept
                   params  (payload operation)}))))))

;; Public API
(defn create-network-step
  ([]
   (create-network-step "/graphql"))
  ([url]
   (HttpNetworkStep. url)))
