(ns artemis.stores.mapgraph-store
  (:require [artemis.stores.protocols :as sp]
            [artemis.document :as d]))

;(enable-console-print!)
;(defn log [args]
;  (.log js/console args))
(def log identity)

;; Normalization code

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

(defn- get-ref
  "Returns a lookup ref for the map, given a collection of identifier
  keys, or nil if the map does not have an identifier key."
  [map id-attrs]
  (when-let [k (find-id-key map id-attrs)]
    [k (get map k)]))

(defn- keept
  "Like clojure.core/keep but preserves the types of vectors and sets,
  including sorted sets. If coll is a map, applies f to each value in
  the map and returns a map of the same (sorted) type."
  [f coll]
  (cond
    (vector? coll) (into [] (keep f) coll)
    (set? coll) (into (empty coll) (keep f) coll)
    (map? coll) (reduce-kv (fn [m k v]
                             (if-let [vv (f v)]
                               (assoc m k vv)
                               m))
                           (empty coll)
                           coll)
    :else (keep f coll)))

(defn- like
  "Returns a collection of the same type as type-coll (vector, set,
  sequence) containing elements of sequence s."
  [type-coll s]
  (cond
    (vector? type-coll) (vec s)
    (set? type-coll) (into (empty type-coll) s)  ; handles sorted-set
    :else s))

(defn- into!
  "Transient version of clojure.core/into"
  [to from]
  (reduce conj! to from))

(defn- update!
  "Transient version of clojure.core/update"
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
            (if-let [refs (seq (keep #(get-ref % id-attrs) v))]
              ;; v is a collection of entities
              (do (when-not (= (count refs) (count v))
                    (throw (ex-info "Collection values may not mix entities and non-entities"
                                    {:reason ::mixed-collection
                                     ::attribute k
                                     ::value v})))
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

(declare write-to-cache)

;; Protocol Implementation

;; This store requires that every graphql query gets sent with the __typename field.
;; It also expects that a list of primary id for each type be given to the cache on initialization
;; IDs are namespaced keys that are formatted as such :<typename>/<primary-key-field>
;; If the necessary primary key field isn't returned in a query, the cache will store the field with a generic key
;; and it will not be retrievable via a normal look up by type and primary key.
(defrecord MapGraphStore [id-attrs entities id-gen]
  sp/GQLStore
  (-query [this document variables return-partial?]
    ;(log "query mapgraph store")
    ;(log document)
    ;(log variables)
    ;(log return-partial?)
    )
  (-write [this data document variables]
    ;(log "write mapgraph store")
    ;(log data)
    ;(log document)
    ;(log variables)
    (write-to-cache document data {:store this :input-vars variables})))


;; Public API

(defn create-store
  ([] (create-store {}))
  ([{:keys [id-attrs entities id-gen] :or {id-attrs #{} entities {} id-gen :id}}]
   (->MapGraphStore (conj id-attrs ::cache) entities id-gen)))

(defn store?
  "Returns true if store is a mapgraph store."
  [store]
  (and (instance? MapGraphStore store)
       (satisfies? sp/GQLStore store)
       (set? (:id-attrs store))
       (map? (:entities store))))

(defn add-id-attr
  "Adds unique identity attributes to the db schema. Returns updated
  db."
  [store & id-keys]
  {:post [(store? %)]}
  (update store :id-attrs into id-keys))

(defn add
  "Returns updated store with normalized entities merged in."
  [store & entities]
  {:post [(store? %)]}
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


(def regular-directives #{"include" "skip"})
(defn aliased? [selection] (:field-alias selection))
(defn has-args? [selection] (:arguments selection))
(defn custom-dirs? [{:keys [directives]}]
  (some #(not (regular-directives (:directive-name %))) directives))

(defn default-val-from-var [var-info]
  (let [default-val (:default-value var-info)]
    (get default-val (:value-type default-val))))

(defn val-from-arg [{:keys [input-vars vars-info]} arg]
  (log "val from arg")
  (let [type (-> arg :argument-value :value-type)
        var-name (-> arg :argument-value :variable-name)]
    (log {:input-vars input-vars :vars-info vars-info :arg arg :type type :var-name var-name})
    (if (= type :variable)
      (or (get input-vars (keyword var-name))               ; get value from input vars
          (-> (group-by :variable-name vars-info)           ; or try to get the default value
              (get var-name)
              first
              default-val-from-var))
      (get (:argument-value arg) type))))

(defn arg-snippet [context arg]
  (let [val (val-from-arg context arg)
        val (if (string? val) (str "\"" val "\"") val)]
    (log "arg snippet")
    (log {:type type :val val :arg arg :var-key (-> arg :argument-value :variable-name keyword)
          :context context})
    (str "\"" (:argument-name arg) "\":" val)))

(defn attach-args-to-key [key context {:keys [arguments]}]
  (let [snippets (map (partial arg-snippet context) arguments)]
    (str key "({" (clojure.string/join "," snippets) "})")))

(defn directive-snippet [context {:keys [directive-name arguments] :as directive}]
  (let [arg-snippets (map (partial arg-snippet context) arguments)
        args-string (if-not (empty? arg-snippets)
                      (str "({" (clojure.string/join "," arg-snippets) "})")
                      "")]
    (str "@" directive-name args-string)))

(defn attach-directive-to-key [key context {:keys [directives]}]
  (let [snippets (map (partial directive-snippet context) directives)]
    (str key (clojure.string/join "" snippets))))

(defn field-key [selection context]
  "returns generated string key if selection is 'wierd' otherwise return keywordized field name"
  (cond-> (:field-name selection)
          (has-args? selection) (attach-args-to-key context selection)
          (custom-dirs? selection) (attach-directive-to-key context selection)
          (not (or (has-args? selection) (custom-dirs? selection)))
          keyword))

(defn modify-map-value [{:keys [store] :as context} selection m]
  "does two things: namespaces the keys according to typename and attaches
   a ::cache key if the map isn't already an entity that can be normalized"
  (let [typename (:__typename m)
        namespaced-map
        (into {} (map (fn [[k v]]
                        (let [new-k (if typename (keyword typename k) k)]
                          (vector new-k v)))
                      (dissoc m :__typename)))]
    (if (not (get-ref namespaced-map (:id-attrs store)))
      (assoc namespaced-map ::cache (::namespaced-key selection))
      namespaced-map)))

(defn modify-field [context result
                    {:keys [field-alias field-name key namespaced-key] :as selection}]
  "modify fields in the result if necessary. this applies to aliased fields, fields with arguments, etc"
  (let [field-alias (keyword field-alias)
        field-name (keyword field-name)
        field-val (if (aliased? selection) (get result field-alias) (get result field-name))
        field-val (if (map? field-val)
                    (modify-map-value context selection field-val)
                    field-val)]
    (log {:key (::key selection) :namespaced-key (::namespaced-key selection)
          :old-result result :new-result     (-> result
                                                 (dissoc field-name)
                                                 (dissoc field-alias)
                                                 (assoc (::key selection) field-val))})
    (if field-val ; only modify field if field val exists
      (-> result
          (dissoc field-name)
          (dissoc field-alias)
          (assoc (::key selection) field-val))
      result)))

(defn update-in-all-vec
  "same as update-in but doesn't require indexes when it comes accross a vector
   it just applies the 'update-in' accross every item in the vector"
  [m [k & ks] f]
  (let [val (get m k)]
    (if (sequential? val) ;; if val is a sequence but not a map
      (if ks
        (assoc m k (map #(update-in-all-vec % ks f) val))
        (assoc m k (map f val)))
      (if ks
        (assoc m k (update-in-all-vec val ks f))
        (assoc m k (f val))))))

(defn modify-fields-reducer [context result [path selections]]
  (let [modify-fields (fn [res selections]
                        (reduce (partial modify-field context) res selections))]
    (log "the path is")
    (log path)
    (if (empty? path)
      (modify-fields result selections)
      (update-in-all-vec result path #(modify-fields % selections)))))

(defn map-vals [m f]
  (into {} (for [[k v] m] [k (f v)])))

(defn combine-maps-of-seqs [list-of-maps]
  "[{:one [1] :two [2]} {:one [1]} {:three [3]}] => {:one [1 1], :two [2], :three [3]}"
  (let [m (apply (partial merge-with concat) list-of-maps)]
    (map-vals m #(if (sequential? %) % (vector %)))))

(defn add-keys-to-selection [context selection stub]
  (let [selection-key (field-key selection context)
        namespaced-selection-key (str stub "." (name selection-key))]
    (assoc selection ::key selection-key
                     ::namespaced-key namespaced-selection-key)))

(defn update-selections
  "goes through the operation and pulls out all the selections
   returns a mapping of <path-to-selection> => <list-of-selections-for-path>
   these selections are also updated to include the key that will be used when
   persisting results to the database."
  ([ctx selection-or-operation]
   (update-selections ctx selection-or-operation [] "root"))
  ([ctx selection path stub]
   (log "find alias")
   (log {:selection selection :path path :stub stub})
   (if (:field-name selection)
     (let [field-name (:field-name selection)
           current-path (conj path (keyword field-name))
           sel-key (field-key selection ctx)
           nsed-sel-key (str stub "." (name sel-key))
           new-selection     (assoc selection ::key sel-key
                                              ::namespaced-key nsed-sel-key)
           pathed-selection {path new-selection}
           pathed-child-selections (map #(update-selections ctx % current-path nsed-sel-key)
                                       (:selection-set selection))
           pathed-selections (conj pathed-child-selections pathed-selection)]
       (log {:current-path           current-path
             :pathed-selection       pathed-selection
             :pathed-selections      pathed-selections
             :child-selections pathed-child-selections})
       (combine-maps-of-seqs pathed-selections))
     (combine-maps-of-seqs
       (map (partial update-selections ctx) (:selection-set selection))))))

(defn write-to-cache
  [document result {:keys [input-vars store] :or {input-vars {} store (create-store)}}]
  (let [first-op (-> document :ast :operations first)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variables first-op)           ; info about the kinds of variables supported by this op
                 :store store}
        root? (= (:operation-type first-op) "query")
        updated-res (if root? (assoc result ::cache :root) result)
        pathed-selections (update-selections context first-op)
        ; sorting these selections becayse we need to reduce over the deepest results first
        sorted-pathed-selections (into (sorted-map-by (comp - count)) pathed-selections)
        updated-res (reduce (partial modify-fields-reducer context) updated-res
                            sorted-pathed-selections)]
    (log "pathed selections")
    (log pathed-selections)
    (log updated-res)
    (log "adding ^^ to store")
    (add store updated-res)))

;helper functions for developing in a repl
;
;
;(defn verify [test-queries k & no-logs?]
;  (log (str "verifying results for " k))
;  (let [{:keys [query input-vars result entities]} (get test-queries k)
;        new-cache (write-to-cache query result {:input-vars input-vars
;                                                :store (create-store {:id-attrs #{:object/id :nested-object/id}})})]
;    (when-not no-logs?
;      (log new-cache)
;      (log "correct: ")
;      (log entities)
;      (log "current: ")
;      (log (:entities new-cache))
;      (log (= entities (:entities new-cache))))
;    (= entities (:entities new-cache))))
;
;(defn verify-all [test-queries]
;  (into {} (map #(vector % (verify test-queries % true))
;                (keys test-queries))))




;; unmodified mapgraph functions related to pulling / querying data
;; will need to update it to work with the graphql persistence format

(defn clear
  "Returns a store with unique indexes and entities cleared out."
  [store]
  (assoc store :entities {} :unique-indexes #{}))

(defn entity?
  "Returns true if map is an entity according to the db schema. An
  entity is a map from keywords to values with exactly one identifier
  key."
  [db map]
  (and (map? map)
       (every? keyword? (keys map))
       (= 1 (count (filter #(contains? map %) (:id-attrs db))))))

(defn ref-to
  "Returns a lookup ref for the entity using the schema in db, or nil
  if not found. The db does not need to contain the entity."
  [db entity]
  (get-ref entity (:id-attrs db)))

(defn ref?
  "Returns true if ref is a lookup ref according to the db schema."
  [db ref]
  (and (vector? ref)
       (= 2 (count ref))
       (contains? (:id-attrs db) (first ref))))

(declare pull)

(defn- pull-join
  "Executes a pull map expression on entity."
  [db result pull-map entity]
  (reduce-kv
    (fn [result k join-expr]
      (if-let [val (get entity k)]
        (if (ref? db val)
          (assoc result k (pull db join-expr val))
          (do (when-not (coll? val)
                (throw (ex-info "pull map pattern must be to a lookup ref or a collection of lookup refs."
                                {:reason ::pull-join-not-ref
                                 ::pull-map-pattern pull-map
                                 ::entity entity
                                 ::attribute k
                                 ::value val})))
              (assoc result k (keept #(pull db join-expr %) val))))
        ;; no value for key
        result))
    result
    pull-map))

(defn pull
  "Returns a map representation of the entity found at lookup ref in
  db. Builds nested maps following a pull pattern.

  A pull pattern is a vector containing any of the following forms:

     :key  If the entity contains :key, includes it in the result.

     '*    (literal symbol asterisk) Includes all keys from the entity
           in the result.

     { :key sub-pattern }
           The entity's value for key is a lookup ref or collection of
           lookup refs. Expands each lookup ref to the entity it refers
           to, then applies pull to each of those entities using the
           sub-pattern."
  [db pattern lookup-ref]
  (when-let [entity (get db lookup-ref)]
    (reduce
      (fn [result expr]
        (cond
          (keyword? expr)
          (if-let [[_ val] (find entity expr)]
            (assoc result expr val)
            result)

          (map? expr)
          (pull-join db result expr entity)

          (= '* expr)  ; don't re-merge things we already joined
          (merge result (apply dissoc entity (keys result)))

          :else
          (throw (ex-info "Invalid form in pull pattern"
                          {:reason ::invalid-pull-form
                           ::form expr
                           ::pattern pattern
                           ::lookup-ref lookup-ref}))))
      {}
      pattern)))
