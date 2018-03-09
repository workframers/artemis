(ns artemis.stores.normalized-in-memory-store
  (:require [artemis.stores.protocols :as sp]))

;; Normalization code

(defn- seek [pred s]
  (some #(when (pred %) %) s))

(defn- find-id-key
  "Returns the first identifier key found in map, or nil if it is not
  a valid entity map."
  [m id-attrs]
  (seek #(contains? m %) id-attrs))

(defn- get-ref
  "Returns a lookup ref for the map, given a collection of identifier
  keys, or nil if the map does not have an identifier key."
  [m id-attrs]
  (when-let [k (find-id-key m id-attrs)]
    [k (get m k)]))

(defn- like
  "Returns a collection of the same type as type-coll (vector, set,
  sequence) containing elements of sequence s."
  [type-coll s]
  (cond
    (vector? type-coll) (vec s)
    (set? type-coll) (into (empty type-coll) s)  ; handles sorted-set
    :else s))

(defn- into!
  "Transient version of clojure.core/into."
  [to from]
  (reduce conj! to from))

(defn- update!
  "Transient version of clojure.core/update."
  [m k f x]
  (assoc! m k (f (get m k) x)))

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
                                     {:reason    ::mixed-map-vals
                                      :attribute k
                                      :value     v})))
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
           (if-let [refs (seq (keep #(get-ref % id-attrs) v))]
             ;; v is a collection of entities
             (do (when-not (= (count refs) (count v))
                   (throw (ex-info "Collection values may not mix entities and non-entities"
                                   {:reason    ::mixed-collection
                                    :attribute k
                                    :value     v})))
                 (recur (into! sub-entities v)
                        (assoc! normalized k (like v refs))
                        (rest kvs)))
             ;; v is a collection of non-entities
             (recur sub-entities
                    (assoc! normalized k v)
                    (rest kvs)))
           ;; v is a single non-entity
           (recur sub-entities
                  (assoc! normalized k v)
                  (rest kvs))))
       (cons (persistent! normalized)
             (mapcat #(normalize-entities % id-attrs)
                     (persistent! sub-entities)))))))

;; Protocol Implementation

(defn- resolve-query [field root-value args store info])


(defn- query-string->document [qs])


(defrecord NormalizedInMemoryStore [unique-indexes entities]
  sp/GQLStore
  (-query [this _ _ _])
  (-write [this _ _ _]))

;; Public API

(defn create-store
  ([]
   (create-store #{}))
  ([unique-indexes]
   {:pre [(set? unique-indexes)]}
   (->NormalizedInMemoryStore unique-indexes {})))

(defn store?
  "Returns true if store is a normalized in-memory store."
  [store]
  (and (instance? NormalizedInMemoryStore store)
       (satisfies? sp/GQLStore store)
       (set? (:unique-indexes store))
       (map? (:entities store))))

;; Add conflict resolution by index
(defn add-unique-index
  "Adds a unique id attribute to create an index for."
  [store & id-keys]
  {:post [(store? %)]}
  (update store :unique-indexes into id-keys))

(defn add
  "Returns an updated store with normalized entities merged in."
  [store & entities]
  {:post [(store? %)]}
  (let [unique-indexes (:unique-indexes store)]
    (update
     store
     :entities
     (fn transient-entities-update
       [ent-m]
       (persistent!
         (reduce (fn [m e]
                   (if-let [lookup-ref (get-ref e unique-indexes)]
                     (update! m lookup-ref merge e)
                     (throw (ex-info "No unique index found for entity to add."
                                     {:reason    ::no-unique-index-found
                                      :attribute unique-indexes
                                      :value     (keys e)}))))
                 (transient ent-m)
                 (mapcat #(normalize-entities % unique-indexes) entities)))))))

(defn clear
  "Returns a store with unique indexes and entities cleared out."
  [store]
  (assoc store :entities {} :unique-indexes #{}))

(defn entity?
  "Returns true if the entity is an entity according to the set of stored
  unique indexes. An entity is a map from keywords to values with exactly one
  unique identifier key."
  [store entity]
  (and (map? entity)
       (every? keyword? (keys entity))
       (= 1 (count (filter #(contains? entity %) (:unique-indexes store))))))

(defn ref-to
  "Returns a lookup ref for the entity using the set of stored unique indexes,
  or nil if not found. The store does not need to contain the entity."
  [store entity]
  (get-ref entity (:unique-indexes store)))

(defn ref?
  "Returns true if lookup-ref is a lookup ref according to the set of stored
  unique indexes. The result of the lookup is not checked."
  [store lookup-ref]
  (and (vector? lookup-ref)
       (= 2 (count lookup-ref))
       (contains? (:unique-indexes store) (first lookup-ref))))
