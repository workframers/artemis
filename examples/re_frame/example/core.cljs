(ns example.core
  (:require [example.queries :as q]
            [example.re-frame-subscriptions]
            [reagent.core :as r]
            [re-frame.core :as rf]))

(defn result-table [result]
  (into [:div.table]
        (mapcat (fn [[title value]]
                  [[:div title]
                   [:div
                    [:pre {:style {:overflow :scroll}}
                     (str value)]]])
                result)))

(defn repo []
  (let [{:keys [data]} @(rf/subscribe [:artemis/query q/get-repo {} {:fetch-policy :remote-only}])
         repository    (:repository data)]
    [:div
     [:header
      [:h2 (:name repository)]
      [:p (:description repository)]]
     ;; Add more stuff here
     ]
    ))

(defn on-load []
  (rf/clear-subscription-cache!)
  (r/render [repo]
            (.getElementById js/document "app")))

(defn ^:export main []
  (enable-console-print!)
  (on-load))
