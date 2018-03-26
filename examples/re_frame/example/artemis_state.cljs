(ns example.artemis-state
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [example.client :refer [client]]
            [artemis.core :as a]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [cljs.core.async :refer [<!]]))

;; Put store and messages into the the app-db so we can subscribe to them
;; and have access from within other subscriptions
(rf/reg-event-db
  ::message-received
  (fn [db [_ query result store]]
    (-> db
        (assoc-in [:artemis/messages query] result)
        (assoc :artemis/store store))))

;; Create a signal graph layer for the store
(rf/reg-sub
  ::artemis-store
  (fn [app-db _]
    (get-in app-db [:artemis/store])))

;; Create a signal graph layer for a read against the store
(rf/reg-sub
  ::artemis-read-result
  :<- [::artemis-store]
  (fn [store [_ doc vars]]
    (when store
      (a/read store doc vars false))))

;; Create a signal graph layer for a message
(rf/reg-sub
  ::artemis-message
  (fn [app-db [_ query]]
    (get-in app-db [:artemis/messages query])))

;; The main subscription interface layered atop the above signal graph
(rf/reg-sub-raw
  ::query
  (fn [_ [_ doc vars opts :as query]]
    (let [kw-args (mapcat identity opts)
          q-chan  (apply a/query client doc vars kw-args)]
      ;; Dispatch into the app-db on message
      (go-loop []
        (when-let [result (<! q-chan)]
          (rf/dispatch [::message-received query result (a/store client)])
          (recur)))
      ;; React to changes in either the store or a new message
      (ratom/make-reaction
        (fn []
          (let [result  @(rf/subscribe [::artemis-read-result doc vars])
                message @(rf/subscribe [::artemis-message query])]
            (merge message result)))))))
