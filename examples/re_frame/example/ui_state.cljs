(ns example.ui-state
  (:require [re-frame.core :as rf]))

;; UI state subscriptions

(rf/reg-sub
  ::ui-state
  (fn [db _]
    (:ui-state db)))

(rf/reg-sub
  ::selected-repo
  :<- [::ui-state]
  (fn [ui-state _]
    (:selected-repo ui-state)))

;; UI state events

(rf/reg-event-db
  :select-repo
  (fn [db [_ repo]]
    (assoc-in db [:ui-state :selected-repo] repo)))

