(ns artemis.stores.mapgraph.write
  (:require [artemis.stores.mapgraph.common :refer [get-ref like map-keys map-vals]]
            [artemis.stores.mapgraph.selections :as sel :refer [has-args? custom-dirs? aliased?]]))

(defn- into!
  "Transient version of clojure.core/into"
  [to from]
  (reduce conj! to from))

(defn- update!
  "Transient version of clojure.core/update"
  [m k f x]
  (assoc! m k (f (get m k) x)))

(defn- ref-and-val [id-attrs val]
  {:ref (get-ref val id-attrs) :val val})

(defn- normalize-entities
  "Returns a sequence of normalized entities starting with map m."
  [m id-attrs]
  (lazy-seq
    (loop [sub-entities (transient [])
           normalized (transient {})
           kvs (seq m)]
      (if-let [[k v] (first kvs)]
        (if (map? v)
          (if-let [r (get-ref v id-attrs)]
            ;; v is a single entity
            (recur (conj! sub-entities v)
                   (assoc! normalized k r)
                   (rest kvs))
            ;; v is a map, not an entity
            (let [values (vals v)]
              (if-let [refs (seq (keep #(get-ref % id-attrs) values))]
                ;; v is a map whose values are entities
                (do (when-not (= (count refs) (count v))
                      (throw (ex-info "Map values may not mix entities and non-entities"
                                      {:reason ::mixed-map-vals
                                       ::attribute k
                                       ::value v})))
                    (recur (into! sub-entities values)
                           (assoc! normalized k (into (empty v)  ; preserve type
                                                      (map vector (keys v) refs)))
                           (rest kvs)))
                ;; v is a plain map
                (recur sub-entities
                       (assoc! normalized k v)
                       (rest kvs)))))
          ;; v is not a map
          (if (coll? v)
            (let [refs-and-vals (map (partial ref-and-val id-attrs) v)
                  new-v (map #(if-let [ref (:ref %)] ref (:val %)) refs-and-vals)
                  refs (->> refs-and-vals
                            (remove #(-> % :ref nil?))
                            (map :val))]
              (recur (into! sub-entities refs)
                     (assoc! normalized k (like v new-v))
                     (rest kvs)))
            ;; v is a single non-entity
            (recur sub-entities
                   (assoc! normalized k v)
                   (rest kvs))))
        (cons (persistent! normalized)
              (mapcat #(normalize-entities % id-attrs)
                      (persistent! sub-entities)))))))

(defn add-id-attr
  "Adds unique identity attributes to the db schema. Returns updated
  db."
  [store & id-keys]
  (update store :id-attrs into id-keys))

(defn add
  "Returns updated store with generic normalized entities merged in."
  [store & entities]
  (let [id-attrs (:id-attrs store)]
    (update
      store
      :entities
      (fn transient-entities-update [ent-m]
        (persistent!
          (reduce (fn [m e]
                    (let [ref (get-ref e id-attrs)]
                      (update! m ref merge e)))
                  (transient ent-m)
                  (mapcat #(normalize-entities % id-attrs) entities)))))))

(defn format-for-cache [{:keys [store] :as context} selection-set result & [stub]]
  "Converts a graphql response into the format that the mapgraph store needs for normalization and querying"
  (let [stub (or stub "root")
        by-alias-or-name (fn [sel] (if (aliased? sel) (:name sel) (:field-name sel)))
        selections (group-by by-alias-or-name selection-set)
        typename (:__typename result)]
    (if (map? result)
      (let [formatted
            (into {} (map (fn [[k v]]
                            (let [sel (->> k name (get selections) first)
                                  sel-key (sel/field-key sel context)
                                  _ (when (nil? sel-key)
                                      (throw (ex-info (str "Key `" k "` found in response, but not in query.")
                                                      {:reason ::key-not-in-query
                                                       ::atribute k
                                                       ::value v})))
                                  new-k (if (and typename
                                                 (not= stub "root") ; don't namespace fields at root level
                                                 (not= :__typename sel-key) ; don't namespace typename
                                                 (not (or (has-args? sel) ; don't namespace fields that require a custom string key
                                                          (custom-dirs? sel))))
                                          (keyword typename sel-key)
                                          sel-key)
                                  nsed-key (str stub "." (name sel-key))
                                  new-v (if (sequential? v)
                                          (mapv (fn [result idx]
                                                  (format-for-cache context (sel/selection-set sel result) result (str nsed-key "." idx)))
                                                v (range))
                                          (format-for-cache context (sel/selection-set sel v) v nsed-key))]
                              (vector new-k new-v)))
                          result))]
        (if (not (get-ref formatted (:id-attrs store)))
          (assoc formatted (:cache-key store) stub)
          formatted))
      result)))

(defn write-to-cache
  "Writes a graphql response to the mapgraph store"
  [document input-vars result store]
  (let [first-op (-> document :ast :operation-definitions first)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variable-definitions first-op)           ; info about the kinds of variables supported by this op
                 :store store}]
    (add store (format-for-cache context (:selection-set first-op) result))))
