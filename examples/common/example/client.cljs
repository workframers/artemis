(ns example.client
  (:require-macros [example.github-token :refer [github-token]])
  (:require [artemis.core :as a]
            [artemis.stores.mapgraph.core :as mgs]
            [artemis.network-steps.http :as http]
            [artemis.network-steps.protocols :as np]))

;; Create a standard store
(def s (mgs/create-store :id-attrs #{:Organization/id
                                     :User/id
                                     :Repository/id
                                     :Language/name}))

;; Create a network step that adds oauth token to all requests
(defn add-token [next-step]
  (reify
    np/GQLNetworkStep
    (-exec [_ operation context]
      (let [up-context (assoc context
                              :with-credentials? false
                              :oauth-token       (github-token))] ;; Make sure your token exists inside of resources/.github-token
        (np/-exec next-step operation up-context)))))

;; Create a network chain using the base http step and the above add-token step
(def n (-> (http/create-network-step "https://api.github.com/graphql")
            add-token))

;; Create the client
(def client (a/create-client :store s :network-chain n))
