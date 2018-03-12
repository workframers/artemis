(ns example.core
  (:require [example.queries :as q]
            [example.re-frame-subscriptions :as sub]
            [reagent.core :as r]
            [re-frame.core :as rf]))

(defn repo [owner repo-name]
  (let [{:keys [data]} @(rf/subscribe [:artemis/query
                                       q/get-repo
                                       {:owner owner :name repo-name}
                                       {:fetch-policy :remote-only}])
         repository    (:repository data)]
    [:div
     [:header
      [:h2 (:name repository)]
      [:p (:description repository)]
      [:strong (:url repository)]
      ]
     ;; Add more stuff here
     ]
    ))

(defn user-repos [login]
  (let [{:keys [data]} @(rf/subscribe [:artemis/query
                                       q/get-repos
                                       {:login login}
                                       {:fetch-policy :remote-only}])
        selected-repo  @(rf/subscribe [::sub/selected-repo])
        user           (:user data)
        repositories   (get-in user [:repositories :nodes])]
    [:div
     [:h1
      "Repos belonging to " (:name user) " (" login ")"]
     [:ul
      (for [repo repositories
            :let [selected? (= repo selected-repo)]]
        [:li {:key      (:id repo)
              :on-click #(rf/dispatch [:select-repo repo])
              :style    (when selected? {:font-weight :bold})}
         (:name repo)])]
     (when selected-repo
       [repo login (:name selected-repo)])]))

(defn on-load []
  (rf/clear-subscription-cache!)
  (r/render [user-repos "colindresj"]
            (.getElementById js/document "app")))

(defn ^:export main []
  (enable-console-print!)
  (on-load))
