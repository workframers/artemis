(ns artemis.stores.mapgraph.write
  (:require [artemis.stores.mapgraph.common :refer [get-ref like map-keys map-vals fragments-map]]
            [clojure.pprint :refer [pprint]]
            [artemis.stores.mapgraph.selections :as sel :refer [has-args? custom-dirs? aliased?]]))

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

(defn add
  "Returns updated store with generic normalized entities merged in."
  [store & entities]
  (update
    store
    :entities
    (fn transient-entities-update [ent-m]
      (persistent!
        (reduce (fn [m e]
                  (let [ref (get-ref e store)]
                    (update! m (:artemis.mapgraph/ref ref) merge e)))
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
                                                       ::atribute k
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
        fragments (fragments-map document)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variable-definitions first-op)           ; info about the kinds of variables supported by this op
                 :store store}]
    (add store (format-for-cache context (:selection-set first-op) result fragments))))

(defn write-to-entity
  [document result [ref-key ref-val] store]
  (let [first-frag (-> document :fragment-definitions first)
        fragments (fragments-map document)
        context {:store store}
        formatted (-> {ref-key ref-val}
                      (merge (format-for-cache context
                                               (:selection-set first-frag)
                                               result
                                               fragments))
                      (dissoc (:cache-key store)))]
    (add store formatted)))
