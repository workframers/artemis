(ns artemis.stores.mapgraph.common
  (:require [com.rpl.specter :as specter]))

(defn map-vals [m f] (specter/transform [specter/MAP-VALS] f m))
(defn map-keys [m f] (specter/transform [specter/MAP-KEYS] f m))

(defn- possible-entity-map?
  "True if x is a non-sorted map. This check prevents errors from
  trying to compare keywords with incompatible keys in sorted maps."
  [x]
  (and (map? x)
       (not (sorted? x))))

(defn get-ref [m {:keys [cache-key id-fn]}]
  (when (possible-entity-map? m)
    (when-let [f (or (cache-key m) (id-fn m))]
      {:artemis.mapgraph/ref f})))

(defn like
  "Returns a collection of the same type as type-coll (vector, set,
  sequence) containing elements of sequence s."
  [type-coll s]
  (cond
    (vector? type-coll) (vec s)
    (set? type-coll) (into (empty type-coll) s)  ; handles sorted-set
    :else s))

(defn extract-name [selection]
  "Uses either the selection :name (for aliases) or :field-name (for non-aliases)."
  (or (:name selection) (:field-name selection)))

(defn fragments-map [document]
  (->> (:fragment-definitions document)
       (map #(vector (:name %) %))
       (into {})))

(defn ref? [r]
  (and (map? r)
       (contains? r :artemis.mapgraph/ref)))

(defn generated? [r]
  (and (ref? r)
       (= (some-> r :artemis.mapgraph/ref first) :artemis.mapgraph/generated)))
