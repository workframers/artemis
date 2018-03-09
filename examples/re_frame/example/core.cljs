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

(defn some-view []
  (let [result (rf/subscribe [:artemis/query q/get-repo {} {:fetch-policy :remote-only}])]
    [:div
     [:style
      "strong { display: block; margin: 15px 0; }
      .table {
        display: grid;
        align-items: center;
        grid-template-columns: 75px minmax(400px, 800px);
        grid-gap: 20px;
      }"]
     [:strong "Latest Result"]
     (when-let [r @result]
       [result-table r])]))

(defn on-load []
  (rf/clear-subscription-cache!)
  (r/render [some-view]
            (.getElementById js/document "app")))

(defn ^:export main []
  (enable-console-print!)
  (on-load))
