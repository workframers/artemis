(ns example.re-frame-subscriptions
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [example.client :refer [client]]
            [artemis.core :as a]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [cljs.core.async :refer [<!]]))

;; Put results into the the app-db so we can subscribe to them
(rf/reg-event-db
  ::result-recevied
  (fn [db [_ rf-key result store]]
    (-> db
        (assoc-in [:artemis/results rf-key] result)
        (assoc :artemis/store store))))

;; Create a signal graph layer for the store
(rf/reg-sub
  ::artemis-store
  (fn [app-db _]
    (get-in app-db [:artemis/store])))

;; Create a signal graph layer for the result
(rf/reg-sub
  ::artemis-result
  (fn [app-db [_ event]]
    (get-in app-db [:artemis/results event])))

;; Create a derived signal layer for the updated store and result
(rf/reg-sub
  ::artemis-signal
  (fn [[_ event] _]
    [(rf/subscribe [::artemis-store])
     (rf/subscribe [::artemis-result event])])
  (fn [[store result] _]
    ;; run query on store
    ;; assoc into data for result
    result
    ))

;; The main subscription interface layered atop the above signal graph
(rf/reg-sub-raw
  :artemis/query
  (fn [_ [_ doc vars opts :as event]]
    (let [kw-args (mapcat identity opts)
          q-chan  (apply a/query client doc vars kw-args)]
      (go-loop []
        (when-let [result (<! q-chan)]
          (rf/dispatch [::result-recevied event result (a/store client)])
          (recur)))
      (ratom/make-reaction
        (fn []
          (let [;store  (get-in @app-db [:artemis/store])
                ;result (get-in @app-db [:artemis/results event])
                ]
            @(rf/subscribe [::artemis-signal event])))))))
