(ns artemis.stores.mapgraph.write
  (:require [artemis.stores.mapgraph.common :refer [get-ref like map-keys map-vals]]
            [artemis.stores.mapgraph.selections :refer [has-args? custom-dirs? aliased? field-key]]))

(defn- into!
  "Transient version of clojure.core/into"
  [to from]
  (reduce conj! to from))

(defn- update!
  "Transient version of clojure.core/update"
  [m k f x]
  (assoc! m k (f (get m k) x)))

(defn ref-and-val [id-attrs val]
  {:ref (get-ref val id-attrs) :val val})

(defn normalize-entities
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

(defn combine-maps-of-seqs [list-of-maps]
  "[{:one [1] :two [2]} {:one [1]} {:three [3]}] => {:one [1 1], :two [2], :three [3]}"
  (let [m (apply (partial merge-with concat) list-of-maps)]
    (map-vals m #(if (sequential? %) % (vector %)))))

(defn add-keys-to-selection [context selection stub]
  "Returns the selection with the field key and the namespaced field key added to it"
  (let [selection-key (field-key selection context)
        namespaced-selection-key (str stub "." (name selection-key))]
    (assoc selection ::key selection-key
                     ::namespaced-key namespaced-selection-key)))

(defn path-selections
  "Goes through the operation and pulls out all the selections
   Returns a mapping of <path> => <list-of-selections-for-path>
   These selections are also updated to include the key that will be used when
   persisting results to the store."
  ([ctx selection-or-operation]
   (path-selections ctx selection-or-operation [] "root"))
  ([ctx selection path stub]
   (if (:field-name selection)
     (let [field-name (:field-name selection)
           current-path (conj path (keyword field-name))
           sel-key (field-key selection ctx)
           nsed-sel-key (str stub "." (name sel-key))
           new-selection     (assoc selection ::key sel-key
                                              ::namespaced-key nsed-sel-key)
           pathed-selection {path new-selection}
           pathed-child-selections (map #(path-selections ctx % current-path nsed-sel-key)
                                        (:selection-set selection))
           pathed-selections (conj pathed-child-selections pathed-selection)]
       (combine-maps-of-seqs pathed-selections))
     (combine-maps-of-seqs
       (map (partial path-selections ctx) (:selection-set selection))))))


(defn modify-map-value [{:keys [store] :as context} selection m & [idx]]
  "Does two things: namespaces the keys according to typename and attaches
   a cache key if the map isn't already an entity that can be normalized"
  (if (map? m)
    (let [typename (:__typename m)
          namespaced-map
          (into {} (map (fn [[k v]]
                          (let [new-k (if (and typename (not= :__typename k))
                                        (keyword typename k)
                                        k)]
                            (vector new-k v)))
                        m))]
      (if (not (get-ref namespaced-map (:id-attrs store)))
        (let [cache-key (if idx
                          (str (::namespaced-key selection) "." idx)
                          (::namespaced-key selection))]
          (assoc namespaced-map (:cache-key store) cache-key))
        namespaced-map))
    m))

(defn modify-field [context result
                    {:keys [field-alias field-name key namespaced-key] :as selection}]
  "modify fields in the result if necessary. this applies to aliased fields, fields with arguments, etc"
  (let [field-alias (keyword field-alias)
        field-name (keyword field-name)
        field-val (if (aliased? selection) (get result field-alias) (get result field-name))
        field-val (cond
                    (sequential? field-val)
                    (map (partial modify-map-value context selection) field-val (range))
                    (map? field-val)
                    (modify-map-value context selection field-val)
                    :else field-val)]
    (if field-val ; only modify field if field val exists
      (-> result
          (dissoc field-name)
          (dissoc field-alias)
          (assoc (::key selection) field-val))
      result)))

(defn mapped-update-in
  "Same as update-in but doesn't require indexes when it comes across a vector
   it just applies the 'update-in' on every item in the vector"
  [m [k & ks] f]
  (let [val (get m k)]
    (if (sequential? val)
      (if ks
        (assoc m k (map #(mapped-update-in % ks f) val))
        (assoc m k (map f val)))
      (if ks
        (assoc m k (mapped-update-in val ks f))
        (assoc m k (f val))))))

(defn modify-fields-reducer [context result [path selections]]
  "Goes through all the pathed selections and updates the graphql result with the necessary modifications"
  (let [modify-fields (fn [res selections]
                        (reduce (partial modify-field context) res selections))]
    (if (empty? path)
      (modify-fields result selections)
      (mapped-update-in result path #(modify-fields % selections)))))


(defn write-to-cache
  "Writes a graphql response to the mapgraph store"
  [document input-vars result store]
  (let [first-op (-> document :ast :operations first)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variables first-op)           ; info about the kinds of variables supported by this op
                 :store store}
        root? (= (:operation-type first-op) "query")
        updated-res (if root? (assoc result (:cache-key store) :root) result)
        pathed-selections (path-selections context first-op)
        ; sorting these selections because we need to reduce over the deepest results first
        sorted-pathed-selections (into (sorted-map-by (comp - count)) pathed-selections)
        updated-res (reduce (partial modify-fields-reducer context) updated-res
                            sorted-pathed-selections)]
    (add store updated-res)))