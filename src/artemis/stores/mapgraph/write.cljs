(ns artemis.stores.mapgraph.write
  (:require [artemis.stores.mapgraph.common :refer [get-ref like map-keys map-vals fragments-map generated? ref?]]
            [artemis.stores.mapgraph.selections :as sel :refer [has-args? custom-dirs? aliased?]]
            [artemis.logging :as logging]))

(defn- into!
  "Transient version of clojure.core/into"
  [to from]
  (reduce conj! to from))

(defn- update!
  "Transient version of clojure.core/update"
  [m k f x]
  (assoc! m k (f (get m k) x)))

(defn- ref-and-val [store val]
  {:ref (get-ref val store) :val val})

(defn- normalize-entities
  "Returns a sequence of normalized entities starting with map m."
  [m store]
  (lazy-seq
    (loop [sub-entities (transient [])
           normalized (transient {})
           kvs (seq m)]
      (if-let [[k v] (first kvs)]
        (if (map? v)
          (if-let [r (get-ref v store)]
            ;; v is a single entity
            (recur (conj! sub-entities v)
                   (assoc! normalized k r)
                   (rest kvs))
            ;; v is a map, not an entity
            (let [values (vals v)]
              (if-let [refs (seq (keep #(get-ref % store) values))]
                ;; v is a map whose values are entities
                (do (when-not (= (count refs) (count v))
                      (throw (ex-info "Map values may not mix entities and non-entities"
                                      {:reason     ::mixed-map-vals
                                       ::attribute k
                                       ::value     v})))
                    (recur (into! sub-entities values)
                           (assoc! normalized k (into (empty v) ; preserve type
                                                      (map vector (keys v) refs)))
                           (rest kvs)))
                ;; v is a plain map
                (recur sub-entities
                       (assoc! normalized k v)
                       (rest kvs)))))
          ;; v is not a map
          (if (coll? v)
            (let [refs-and-vals (map (partial ref-and-val store) v)
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
              (mapcat #(normalize-entities % store)
                      (persistent! sub-entities)))))))


(defn resolve-merge [store display-name existing-entities new-entity]
  (let [ref (get-ref new-entity store)
        old-entity (->> ref :artemis.mapgraph/ref (get existing-entities))
        inconsistent-refs (filter (fn [[k v]] ;; if the new ref and old ref for this value don't have the same generated state, warn
                                    (when (ref? v)
                                      (let [old-val (get old-entity k)]
                                        (and (some? old-val)
                                             (not= (generated? v)
                                                   (generated? old-val))))))
                                  new-entity)]
    (doall
     (for [inconsistent-ref inconsistent-refs]
       (logging/warn (str (when display-name
                            (str display-name " | "))
                          "New result at key `"
                          (first inconsistent-ref)
                          "` under `"
                          (:artemis.mapgraph/ref ref)
                          "` likely to overwrite data"))))
    (update! existing-entities (:artemis.mapgraph/ref ref) merge new-entity)))

(defn add
  "Returns updated store with generic normalized entities merged in."
  [store display-name & entities]
  (update
   store
   :entities
   (fn transient-entities-update [ent-m]
     (persistent!
      (reduce (partial resolve-merge store display-name)
              (transient ent-m)
              (mapcat #(normalize-entities % store) entities))))))

(defn- name-or-field-name [sel]
  (if (aliased? sel)
    (:name sel)
    (:field-name sel)))

(defn format-for-cache [{:keys [store apply-typename?] :as context} selection-set result fragments & [stub]]
  "Converts a graphql response into the format that the mapgraph store needs for normalization and querying"
  (let [stub (or stub "root")
        selections (->> selection-set
                        (sel/resolve-fragments fragments)
                        (group-by name-or-field-name))]
    (if (map? result)
      (let [formatted
            (into {} (map (fn [[k v]]
                            (let [sel (->> k name (get selections) first)
                                  sel-key (sel/field-key sel context)
                                  _ (when (nil? sel-key)
                                      (throw (ex-info (str "Key `" k "` found in response, but not in document.")
                                                      {:reason ::key-not-in-document
                                                       ::attribute k
                                                       ::value v})))
                                  nsed-key (str stub "." (name sel-key))
                                  new-v (if (sequential? v)
                                          (mapv (fn [result idx]
                                                  (format-for-cache context (sel/selection-set sel result) result fragments (str nsed-key "." idx)))
                                                v (range))
                                          (format-for-cache context (sel/selection-set sel v) v fragments nsed-key))]
                              (vector sel-key new-v)))
                          result))]
        (if (or (= stub "root") (not (get-ref formatted store)))
          (assoc formatted (:cache-key store) [:artemis.mapgraph/generated stub])
          formatted))
      result)))

(defn write-to-cache
  "Writes a graphql response to the mapgraph store"
  [document input-vars result store]
  (let [first-op (-> document :operation-definitions first)
        query-name (get-in first-op [:operation-type :name])
        fragments (fragments-map document)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variable-definitions first-op)           ; info about the kinds of variables supported by this op
                 :store store}]
    (add store
         (when query-name (str "query:" query-name))
         (format-for-cache context (:selection-set first-op) result fragments))))

(defn write-to-entity
  [document result [ref-key ref-val] store]
  (let [first-frag (-> document :fragment-definitions first)
        fragment-name (:name first-frag)
        fragments (fragments-map document)
        context {:store store}
        formatted (-> {ref-key ref-val}
                      (merge (format-for-cache context
                                               (:selection-set first-frag)
                                               result
                                               fragments))
                      (dissoc (:cache-key store)))]
    (add store
         (when fragment-name (str "fragment:" fragment-name))
         formatted)))
