(ns example.core
  (:require [example.queries :as q]
            [example.re-frame-subscriptions]
            [reagent.core :as r]
            [re-frame.core :as rf]))

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

(defn user-repos [login]
  (let [{:keys [data]} @(rf/subscribe [:artemis/query
                                       q/get-repos
                                       {:login login}
                                       {:fetch-policy :remote-only}])
        user           (:user data)
        repositories   (get-in user [:repositories :nodes])]
    [:div
     [:h1
      "Repos belonging to " (:name user) " (" login ")"]
     (.log js/console "view: " data)
     [:ul
      (for [repo repositories]
        [:li {:key (:id repo)}
         (:name repo)])]]))

(defn on-load []
  (rf/clear-subscription-cache!)
  (r/render [user-repos "colindresj"]
            (.getElementById js/document "app")))

(defn ^:export main []
  (enable-console-print!)
  (on-load))
