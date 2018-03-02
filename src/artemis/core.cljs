(ns artemis.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [artemis.stores.mapgraph-store :as nms]
            [artemis.stores.protocols :as sp]
            [artemis.network-steps.http :as http]
            [artemis.network-steps.protocols :as np]
            [artemis.document :as d]
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
                               :options   (s/keys* :opt [::name
                                                         ::out-chan
                                                         ::fetch-policy
                                                         ::context])))
        :ret  ::out-chan)

(defn query
  "Given a client, document, and optional arg and opts, returns a channel that
  will receive the response(s) for a query. Dependending on the :fetch-policy,
  the channel will receive one or more messages."
  ([client document]
   (query client document {}))
  ([client document & args]
   (let [variables   (when (map? (first args)) (first args))
         options     (if variables (next args) args)
         local-read  #(sp/-query @(:store client)
                                 document
                                 variables
                                 (get options :return-partial? false))
         remote-read #(np/-exec (:network-chain client)
                                {:document  document
                                 :variables variables
                                 :name      (:name options)}
                                (get options :context {}))
         {:keys [out-chan fetch-policy context]
          :or   {out-chan     (async/chan)
                 fetch-policy :local-only}} options]
     (case fetch-policy
       :local-only
       (let [local-result (local-read)]
         ;; TODO Handle failed local-result
         (async/put! out-chan
                     {:data           local-result
                      :variables      variables
                      :in-flight?     false
                      :network-status :ready})
         (async/close! out-chan))

       ; :local-first
       ; (do)

       :local-then-remote
       (let [local-result       (local-read)
             remote-result-chan (remote-read)]
         ;; TODO Handle failed local-result
         (async/put! out-chan {:data           local-result
                               :variables      variables
                               :in-flight?     true
                               :network-status :loading})
         ;; TODO Handle failed remote-result
         (go (let [remote-result (async/<! remote-result-chan)]
               (async/put! out-chan {:data           remote-result
                                     :variables      variables
                                     :in-flight?     false
                                     :network-status :ready})
               (async/close! out-chan))))

       :remote-only
       (let [remote-result-chan (remote-read)]
         (async/put! out-chan {:data           nil
                               :variables      variables
                               :in-flight?     true
                               :network-status :loading})
         ;; TODO Handle failed remote-result
         (go (let [remote-result (async/<! remote-result-chan)]
               (async/put! out-chan {:data           remote-result
                                     :variables      variables
                                     :in-flight?     false
                                     :network-status :ready})
               (async/close! out-chan))))

       (throw (ex-info "Invalid :fetch-policy provided. Must be one of
                       #{:local-only :local-first :local-then-remote :remote-only}."
                       {:reason    ::invalid-fetch-policy
                        :attribute :fetch-policy
                        :value     fetch-policy})))
     out-chan)))
