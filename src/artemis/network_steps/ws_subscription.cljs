(ns artemis.network-steps.ws-subscription
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [haslett.client :as ws]
            [haslett.format :as fmt]
            [artemis.core :as a]
            [artemis.result :as ar]
            [artemis.network-steps.protocols :as np]
            [cljs.core.async :as async]))

(defonce ws-conn     (atom nil))
(defonce live-subs   (atom {}))
(defonce subs-buffer (atom {}))

(defn log-dupe! [msg]
  (when ^boolean goog.DEBUG
    (.info js/console (str "%c[ARTEMIS] " msg) "color: orange;")))

(defn- kebab-keyword [s]
  (when s
    (-> s (.replace #"_" "-") keyword)))

(defn- ws-txf [unpack]
  (map
   (fn [{:keys [type payload]}]
     (let [message    (if (= type "error")
                        (ar/with-errors {:data nil} [payload])
                        {:data (unpack (:data payload))})
           ws-status  (if @ws-conn :connected :disconnected)]
       (assoc message :ws-status  ws-status
                      :ws-message (kebab-keyword type))))))

(defn- start-subscription [stream operation chan+id]
  (if (contains? @live-subs operation)
    (log-dupe! (str "Ignoring duplicate subscription: " (:id chan+id)))
    (do (swap! live-subs assoc operation chan+id)
        (go (async/>! (:sink stream)
                      {:id      (:id chan+id)
                       :type    "start"
                       :payload (:graphql operation)})
            (async/pipeline 1
                            (:chan chan+id)
                            (ws-txf (:unpack operation))
                            (:source stream) false) ; false only when reconnect false?
            ))))

(defrecord
  ^{:added "0.1.0"}
  WSSubscriptionStep
  []
  np/GQLNetworkStep
  (-exec [this operation context]
    (let [c       (async/chan)
          stream  @ws-conn
          chan+id {:chan c, :id (:ws-sub-id context)}]
      (if stream
        (start-subscription stream operation chan+id)
        (swap! subs-buffer conj [operation chan+id]))
      c)))

(defn- flush-buffer! [stream]
  (doseq [[operation chan+id] @subs-buffer]
    (start-subscription stream operation chan+id)))

(defn- shake-hands!
  "Establishes the WebSocket connection and puts an initializing message. The
  connection stream is added to the registry and any subscriptions that have
  been buffered until connection ready are flushed."
  [uri init-payload formatter reconnect?]
  (when-not @ws-conn
    (go (let [stream (async/<! (ws/connect uri {:format    formatter
                                                :protocols ["graphql-ws"]}))]
          (async/>! (:sink stream)
                    {:type    "connection_init"
                     :payload init-payload})
          (reset! ws-conn stream)
          (flush-buffer! stream)
          (when reconnect?
            (let [_ (async/<! (:close-status stream))]
              (reset! ws-conn nil)
              (reset! subs-buffer @live-subs)
              (reset! live-subs {})
              (shake-hands! uri init-payload formatter reconnect?)))))))

(def ^:private json-formatter
  (reify fmt/Format
    (read  [_ s] (js->clj (.parse js/JSON s) :keywordize-keys true))
    (write [_ v] (.stringify js/JSON (clj->js v)))))

;; Public API
(defn create-ws-subscription-step
  "Returns a new `WSSubscriptionStep` for a given uri and options. The uri
  defaults to `\"/graphql/subscriptions\"`. Uses haslett channels for WebSocket
  connection and exchange."
  {:added "0.1.0"}
  ([]
   (create-ws-subscription-step "/graphql/subscriptions"))
  ([uri & {:keys [interchange-format init-payload reconnect?]
           :or   {interchange-format :json
                  init-payload       {}
                  reconnect?         true}}]
   (let [formatter (case interchange-format
                     :json json-formatter
                     :edn  fmt/edn
                     (throw (ex-info (str "Invalid data interchange format. "
                                          "Must be one of #{:json :edn}.")
                                     {:value  interchange-format})))
         step      (WSSubscriptionStep.)]
     (shake-hands! uri init-payload formatter reconnect?)
     step)))

(defn with-ws-subscriptions
  "Returns a network chain that directs subcription operations to the
  ws-sub-step and all others to the http-step."
  {:added "0.1.0"}
  [http-step ws-sub-step]
  (reify
    np/GQLNetworkStep
    (-exec [_ operation context]
      (let [next-step (if (:ws-sub-id context) ws-sub-step http-step)]
        (a/exec next-step operation context)))))

(defn close-connection!
  "Closes the WebSocket connection and returns a channel with the status at
  close."
  {:added "0.1.0"}
  []
  (let [return (ws/close @ws-conn)
        unsubs @live-subs]
    (reset! ws-conn false) ; set to false so that we don't try to reconnect
    (reset! subs-buffer {})
    (reset! live-subs {})
    (doseq [[_ {:keys [chan]}] unsubs]
      (go (async/>! chan {:data nil, :ws-status :manually-closed, :ws-message nil})
          (async/close! chan)))
    return))

(defn- find-sub [id]
  (->> @live-subs
       (filter (fn [[_ chan+id]] (= (:id chan+id) id)))
       first))

(defn unsubscribe!
  "Given a `ws-id`, find the subscription and close the channel, remove from
  the live-subs registry, and send a stop message to the server."
  {:added "0.1.0"}
  [ws-id]
  (when-let [stream @ws-conn]
    (when-let [[operation chan+id] (find-sub ws-id)]
      (swap! live-subs dissoc operation)
      (async/close! (:chan chan+id))
      (go (async/>! (:sink stream)
                    {:id   (:id chan+id)
                     :type "stop"})))))
