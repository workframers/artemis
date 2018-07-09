(ns artemis.logging
  (:require [clojure.string :as string]
            [shodan.console :as console]
            [diffit.map :refer [diff]]))

(def ^:private css-values
  {:light  "font-weight:lighter"
   :bold   "font-weight:bolder"
   :italic "font-style:italic"
   :grey   "color:#888"})

(defn- css [& kws]
  (string/join ";" (map css-values kws)))

(defn- operation-names [document]
  (->> (:operation-definitions document)
       (map (comp :name :operation-type))
       (string/join ",")))

(defn- log-single! [a b]
  (console/log "%c[%s]" (css :light :grey)
               (name a)
               b))

(defn- log-group! [title & to-log]
  (console/group-start-collapsed "%c%s"
                                 (css :light :grey)
                                 title)
  (doseq [[a b] (partition 2 to-log)]
    (log-single! a b)))

(defn log-end! []
  (when goog.DEBUG (console/group-end)))

(defn log-start! [kind document]
  (when goog.DEBUG
    (console/group-start-collapsed "%cartemis.core/%s!"
                                   (css :light :grey)
                                   (name kind)
                                   (operation-names document))))

(defn log-query! [document variables fetch-policy]
  (when goog.DEBUG
    (log-group! "query info"
                :document     document
                :variables    variables
                :fetch-policy fetch-policy)
    (log-end!)))

(defn log-mutation! [document variables]
  (when goog.DEBUG
    (log-group! "query info"
                :document  document
                :variables variables)
    (log-end!)))

(defn log-store-before!
  [store & [optimistic?]]
  (when goog.DEBUG
    (log-group! (str "store update" (when optimistic? " (optimistic)"))
                :before store)))

(defn log-store-after! [old-store store]
  (when goog.DEBUG
    (log-single! :after store)
    (when-let [old-entities (:entities old-store)]
      (log-single! :entity-diff (diff old-entities
                                      (:entities store))))
    (log-end!)))

(defn log-store-local-only! [store]
  (when goog.DEBUG
    (log-store-before! store)
    (console/info "%query with fetch-policy :local-only will never yield a store update"
                  (css :light :grey :italic))
    (log-store-after! store store)))
