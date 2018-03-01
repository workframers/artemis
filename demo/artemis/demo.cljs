(ns artemis.demo
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [artemis.github-token :refer [github-token]])
  (:require [artemis.core :as a]
            [artemis.stores.normalized-in-memory-store :as nms]
            [artemis.network-steps.http :as http]
            [artemis.network-steps.protocols :as np]
            [artemis.document :refer [parse-document]]
            [cljs.core.async :refer [<!]]))

;; Create a standard store
(def s (nms/create-store))

;; Create a network step that adds oauth token to all requests
(defn add-token [next-step]
  (reify
    np/GQLNetworkStep
    (-exec [_ operation context]
      (let [up-context (assoc context
                              :interchange-format :transit
                              :with-credentials? false
                              :oauth-token       (github-token))] ;; Make sure your token exists inside of resources/.github-token
        (np/-exec next-step operation up-context)))))

;; Create a network chain using the base http step and the above add-token step
(def n (-> (http/create-network-step "https://api.github.com/graphql")
            add-token))

;; Create the client
(def c (a/create-client s n))

;; Write a GraphQL query
(def doc
  (parse-document
   "query {
      repository(owner: \"octocat\", name: \"Hello-World\") {
        id
        name
        description
        createdAt
        url
        sshUrl
        pushedAt
        labels(first:5) {
          nodes {
            id
            name
            repository {
              id
              name
            }
          }
        }
        stargazers(first:5) {
          nodes {
            id
            name
            email
              repositories(first:2) {
                nodes {
                  id
                  name
                }
              }
          }
        }
      }
    }"))

;; Run the query
(defn query! []
  (let [c (a/query c doc :fetch-policy :remote-only)]
    (go-loop []
      (when-let [result (<! c)]
        (.log js/console result)
        (recur)))))
