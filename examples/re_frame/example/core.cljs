(ns example.core
  (:require [example.artemis-state :as artemis]
            [example.queries :as q]
            [example.ui-state :as ui]
            [reagent.core :as r]
            [re-frame.core :as rf]))

(defn repo [owner repo-name]
  (let [{:keys [data]} @(rf/subscribe [::artemis/query
                                       q/get-repo
                                       {:owner owner :name repo-name}
                                       {:fetch-policy :local-first}])
         repository    (:repository data)
         languages     (get-in repository [:languages :nodes])
         stargazers    (get-in repository [:stargazers :nodes])]
    [:div
     [:header
      [:h2 (:name repository)]
      [:p (:description repository)]
      [:a {:href (:url repository)}
       (:url repository)]]
     [:ul {:style {:padding 0}}
      (for [l languages]
        [:li {:key (:name l)
              :style {:background-color (:color l)
                      :display :inline-block
                      :padding 5
                      :margin-right 5}}
         (:name l)])]
     [:h3 "Stargazers"]
     [:ul
      (for [s stargazers]
        [:li {:key (:id s)}
         (:name s)])]]))

(defn user-repos [login]
  (let [{:keys [data]} @(rf/subscribe [::artemis/query
                                       q/get-repos
                                       {:login login}
                                       {:fetch-policy :local-first}])
        selected-repo  @(rf/subscribe [::ui/selected-repo])
        user           (:user data)
        repositories   (get-in user [:repositories :nodes])]
    [:div
     [:header
      [:h1 {:style {:display :flex
                    :align-items :center}}
       "Repos belonging to " login
       [:img {:src (:avatarUrl user)
              :height 45
              :style {:margin-left 10}}]]]
     [:main {:style {:display :grid
                     :grid-template-columns "300px auto"}}
      [:ul
       (for [repo repositories
             :let [selected? (= repo selected-repo)]]
         [:li {:key      (:id repo)
               :on-click #(rf/dispatch [:select-repo repo])
               :style    (when selected? {:font-weight :bold})}
          (:name repo)])]
      (when selected-repo
        [repo login (:name selected-repo)])]]))

(defn on-load []
  (rf/clear-subscription-cache!)
  (r/render [user-repos "octocat"]
            (.getElementById js/document "app")))

(defn ^:export main []
  (enable-console-print!)
  (on-load))
