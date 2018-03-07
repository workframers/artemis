(ns artemis.test-util
  (:require-macros artemis.test-util)
  (:require [artemis.network-steps.protocols :refer [GQLNetworkStep]]
            [artemis.stores.protocols :refer [GQLStore]]
            [artemis.result :refer [with-errors]]
            [artemis.document :refer [parse-document]]))

(defn stub-net-chain [mock-chan]
  (reify GQLNetworkStep
    (-exec [_ _ _] mock-chan)))

(defrecord Store [query-fn write-fn]
  GQLStore
  (-query [this doc variables _]
    (if (fn? query-fn)
      (query-fn this doc variables _)
      {:data nil}))
  (-write [this result doc variables]
    (if (fn? write-fn)
      (write-fn this result doc variables)
      this)))

(defn stub-store [& args] (apply ->Store args))

(def query-doc
  (parse-document
    "query Hero($episode: String!) {
      hero(episode: $episode) {
        name
      }
    }"))

(def mutation-doc
  (parse-document
    "mutation CreateReview($episode: String!, $review: ReviewInput!) {
      createReview(episode: $episode, review: $review) {
        stars
        commentary
      }
    }"))
