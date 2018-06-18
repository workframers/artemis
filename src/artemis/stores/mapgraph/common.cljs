(ns artemis.stores.mapgraph.common)

(defn map-vals [m f] (into {} (for [[k v] m] [k (f v)])))
(defn map-keys [m f] (into {} (for [[k v] m] [(f k) v])))

(defn- seek [pred s]
  (some #(when (pred %) %) s))

(defn- possible-entity-map?
  "True if x is a non-sorted map. This check prevents errors from
  trying to compare keywords with incompatible keys in sorted maps."
  [x]
  (and (map? x)
       (not (sorted? x))))

(defn- find-id-key
  "Returns the first identifier key found in map, or nil if it is not
  a valid entity map."
  [map id-attrs]
  (when (possible-entity-map? map)
    (seek #(contains? map %) id-attrs)))

(defn get-ref
  "Returns a lookup ref for the map, given a collection of identifier
  keys, or nil if the map does not have an identifier key."
  [map id-attrs]
  (when-let [k (find-id-key map id-attrs)]
    [k (get map k)]))

(defn like
  "Returns a collection of the same type as type-coll (vector, set,
  sequence) containing elements of sequence s."
  [type-coll s]
  (cond
    (vector? type-coll) (vec s)
    (set? type-coll) (into (empty type-coll) s)  ; handles sorted-set
    :else s))

(defn fragments-map [document]
  (->> (:fragment-definitions document)
       (map #(vector (:name %) %))
       (into {})))
