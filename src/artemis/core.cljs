(ns artemis.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [artemis.stores.mapgraph.core :as nms]
            [artemis.stores.protocols :as sp]
            [artemis.network-steps.http :as http]
            [artemis.network-steps.protocols :as np]
            [artemis.document :as d]
            [artemis.result :refer [result->message]]
            [clojure.spec.alpha :as s]
            [cljs.core.async :as async]
            [cljs.core.async.impl.protocols :refer [WritePort ReadPort]]))

(defrecord Client [store network-chain])

(s/def ::store #(satisfies? sp/GQLStore %))
(s/def ::network-chain #(satisfies? np/GQLNetworkStep %))
(s/def ::client #(instance? Client %))

(s/fdef create-client
        :args (s/alt
               :arity-0 (s/cat)
               :arity-1 (s/cat :store ::store)
               :arity-2 (s/cat :store         ::store
                               :network-chain ::network-chain))
        :ret  ::client)

(defn create-client
  "Returns a new client specified by store, network-chain, and options."
  ([]
   (create-client (nms/create-store)))
  ([store]
   (create-client store (http/create-network-step)))
  ([store network-chain]
   (Client. (atom store) network-chain)))

(s/fdef store
        :args (s/cat :client ::client)
        :ret  (s/or :nil   nil?
                    :store ::store))

(defn store
  "Retrieves the current :store value for client. Returns nil if no store
  exists."
  [client]
  (some-> client :store deref))

(s/fdef network-chain
        :args (s/cat :client ::client)
        :ret  (s/or :nil           nil?
                    :network-chain ::network-chain))

(defn network-chain
  "Retrieves the current :network-chain value for client. Returns nil if no
  network chain exists."
  [client]
  (:network-chain client))

(s/fdef client?
        :args (s/cat :x any?)
        :ret  boolean?)

(defn client?
  "Returns true if x is a valid client."
  [x]
  (instance? Client x))

(s/def ::document d/doc?)
(s/def ::name string?)
(s/def ::out-chan (s/and #(satisfies? WritePort %) #(satisfies? ReadPort %)))
(s/def ::fetch-policy #{:local-only :local-first :local-then-remote :remote-only})
(s/def ::context map?)

(s/fdef query
        :args (s/alt
               :arity-2 (s/cat :client   ::client
                               :document ::document)
               :arity-n (s/cat :client    ::client
                               :document  ::document
                               :variables (s/? map?)
                               :options   (s/keys* :opt-un [::name
                                                            ::out-chan
                                                            ::fetch-policy
                                                            ::context])))
        :ret  ::out-chan)

;; TODO - rename query!
(defn query
  "Given a client, document, and optional arg and opts, returns a channel that
  will receive the response(s) for a query. Dependending on the :fetch-policy,
  the channel will receive one or more messages.

  Fetch Policies:

  - `:local-only`: A query will never be executed remotely. Instead, the query
  will only run against the local store. If the query can't be satisfied
  locally, an exception will be thrown. This fetch policy allows you to only
  interact with data in your local store without making any network requests
  which keeps your component fast, but means your local data might not be
  consistent with what is on the server. For this reason, this policy should
  only be used on data that is highly unlikely to change, or is regularly being
  refreshed.

  - `:local-first`: Will run a query against the local store first. The result
  of the local query will be placed on the return channel. If that result is
  a non-nil value, then a remote query will not be executed. If the result is
  nil, meaning the data isn't available locally, a remote query will be
  executed. This fetch policy aims to minimize the number of network requests
  sent. The same cautions around stale data that applied to the :local-only
  policy do so for this policy as well.

  - `:local-then-remote`: Like the :local-first policy, this will run a query
  against the local store first and place the result on the return channel.
  However, unlike :local-first, a remote query will always be executed,
  regardless of the the value of the local result. This fetch policy optimizes
  for users getting a quick response while also trying to keep cached data
  consistent with your server data at the cost of extra network requests.

  - `:remote-only`: This fetch policy will never run against the local store.
  Instead, it will always execte a remote query. This policy optimizes for data
  consistency with the server, but at the cost of an instant response.
  "
  ([client document]
   (query client document {}))
  ([client document & args]
   (let [variables   (when (map? (first args)) (first args))
         options     (if variables (next args) args)
         query-name  (:name options)
         local-read  #(sp/-query @(:store client)
                                 document
                                 variables
                                 (get options :return-partial? false))
         remote-read #(np/-exec (:network-chain client)
                                {:document  document
                                 :variables variables
                                 :name      query-name}
                                (get options :context {}))
         {:keys [out-chan fetch-policy context]
          :or   {out-chan     (async/chan)
                 fetch-policy :local-only}} options]
     (case fetch-policy
       :local-only
       (let [local-result (local-read)]
         (async/put! out-chan
                     (-> local-result
                         result->message
                         (assoc :variables      variables
                                :source         :local
                                :in-flight?     false
                                :network-status :ready)))
         (async/close! out-chan))

       :local-first
       (let [local-result    (local-read)
             nil-local-data? (nil? (:data local-result))]
         (async/put! out-chan
                     (-> local-result
                         result->message
                         (assoc :variables      variables
                                :source         :local
                                :in-flight?     nil-local-data?
                                :network-status (if nil-local-data? :fetching :ready))))
         (if nil-local-data?
           (let [remote-result-chan (remote-read)]
             (go (let [remote-result (async/<! remote-result-chan)]
                   (async/put! out-chan
                               (-> remote-result
                                   result->message
                                   (assoc :variables      variables
                                          :source         :remote
                                          :in-flight?     false
                                          :network-status :ready)))
                   (async/close! out-chan))))
           (async/close! out-chan)))

       :local-then-remote
       (let [local-result       (local-read)
             remote-result-chan (remote-read)]
         (async/put! out-chan
                     (-> local-result
                         result->message
                         (assoc :variables      variables
                                :source         :local
                                :in-flight?     true
                                :network-status :fetching)))
         (go (let [remote-result (async/<! remote-result-chan)]
               (async/put! out-chan
                           (-> remote-result
                               result->message
                               (assoc :variables      variables
                                      :source         :remote
                                      :in-flight?     false
                                      :network-status :ready)))
               (async/close! out-chan))))

       :remote-only
       (let [remote-result-chan (remote-read)]
         (async/put! out-chan {:data           nil
                               :variables      variables
                               :source         :local
                               :in-flight?     true
                               :network-status :fetching})
         (go (let [remote-result (async/<! remote-result-chan)]
               (async/put! out-chan
                           (-> remote-result
                               result->message
                               (assoc :variables      variables
                                      :source         :remote
                                      :in-flight?     false
                                      :network-status :ready)))
               (async/close! out-chan))))

       (throw (ex-info (str "Invalid :fetch-policy. Must be one of #{:local-only"
                            " :local-first :local-then-remote :remote-only}.")
                       {:reason ::invalid-fetch-policy
                        :value  fetch-policy})))
     out-chan)))
