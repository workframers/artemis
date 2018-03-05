(ns artemis.stores.mapgraph-store
  (:require [artemis.stores.protocols :as sp]
            [artemis.document :as d]))

;; Normalization code

(enable-console-print!)

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


;; Protocol Implementation

(defn- resolve-query [field root-value args store info])


(defn- query-string->document [qs])

(defn- gql-operation->pull-pattern [op]

  )


(defrecord MapGraphStore [id-attrs entities id-gen]
  sp/GQLStore
  (-query [this document variables return-partial?]
    ;(log "query mapgraph store")
    ;(log document)
    ;(log (keys document))
    ;(log (:ast document))
    ;(log variables)
    ;(log return-partial?)
    )
  (-write [this data document variables]
    ;(log "write mapgraph store")
    ;(log data)
    ;(log document)
    ;(log variables)
    ))
; (js/graphqlAnywhere resolve-query
;                     (query-string->document qs)
;                     nil
;                     this
;                     qv
;                     nil)


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

(defn new-db
  "Returns a new, empty database value."
  []
  {:id-attrs #{}})

(defn db?
  "Returns true if x is a mapgraph database."
  [x]
  (and (map? x)
       (set? (:id-attrs x))
       (every? keyword? (:id-attrs x))))

(defn add-id-attr
  "Adds unique identity attributes to the db schema. Returns updated
  db."
  [store & id-keys]
  {:post [(store? %)]}
  (update store :id-attrs into id-keys))

(defn add
  "Returns updated db with normalized entities merged in."
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

(def ghdoc
  (d/parse-document
    "query {
       repository(owner: \"octocat\", name: \"Hello-World\") {
         id
         name
         description
         createdAt
         url
         sshUrl
         pushedAt
         labels(first:5) {
           nodes {
             id
             name
             repository {
               id
               name
             }
           }
         }
         stargazers(first:5) {
           nodes {
             id
             name
             email
               repositories(first:2) {
                 nodes {
                   id
                   name
                 }
               }
           }
         }
       }
     }"))

(def aliased-ghdoc
  (d/parse-document
    "query {
       repository(owner: \"octocat\", name: \"Hello-World\") {
         id
         aliasedName: name
         aliasedDesc: description
         createdAt
         aliasedUrl: url
         sshUrl
         pushedAt
         labels(first:5) {
           aliasedNodes: nodes {
             nodeID: id
             name
             repository {
               repoID: id
               name
             }
           }
         }
         stargazers(first:5) {
           nodes {
             id
             name
             email
               repositories(first:2) {
                 nodes {
                   id
                   name
                 }
               }
           }
         }
       }
     }"))

(def test-queries
  {
   ;:named
   ;{:query (d/parse-document
   ;          "{test(arg: \"something\") {
   ;             id
   ;             aliasedField: stringField
   ;             numberField
   ;             nullField
   ;           }}")}

   :basic
   {:query    (d/parse-document
                "{
                   id
                   stringField
                   numberField
                   nullField
                 }")
    :result   {:id          "abcd"
               :stringField "this is a string"
               :numberField 3
               :nullField   nil}
    :entities {[::cache :root]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                ::cache      :root}}}

   :args
   {:query    (d/parse-document
                "{
                   id
                   stringField(arg: 1)
                   numberField
                   nullField
                 }")
    :result   {:id          "abcd"
               :stringField "The arg was 1"
               :numberField 3
               :nullField   nil}
    :entities {[::cache :root]
               {:id                        "abcd"
                "stringField({\"arg\":1})" "The arg was 1"
                :numberField               3
                :nullField                 nil
                ::cache                    :root}}}

   :aliased
   {:query    (d/parse-document
                "{
                   id
                   aliasedField: stringField
                   numberField
                   nullField
                 }")
    :result   {:id           "abcd"
               :aliasedField "this is a string"
               :numberField  3
               :nullField    nil}
    :entities {[::cache :root]
               {:id          "abcd"
                :stringField "this is a string"
                :numberField 3
                :nullField   nil
                ::cache      :root}}}

   :aliased-with-args
   {:query    (d/parse-document
                "{
                   id
                   aliasedField1: stringField(arg:1)
                   aliasedField2: stringField(arg:2)
                   numberField
                   nullField
                 }")
    :result   {:id            "abcd"
               :aliasedField1 "The arg was 1"
               :aliasedField2 "The arg was 2"
               :numberField   3
               :nullField     nil}
    :entities {[::cache :root]
               {:id                        "abcd"
                "stringField({\"arg\":1})" "The arg was 1"
                "stringField({\"arg\":2})" "The arg was 2"
                :numberField               3
                :nullField                 nil
                ::cache                    :root}}}

   :with-vars
   {:query      (d/parse-document
                  "{
                     id
                     stringField(arg: $stringArg)
                     numberField(intArg: $intArg, floatArg: $floatArg)
                     nullField
                   }")
    :input-vars {:intArg    5
                 :floatArg  3.14
                 :stringArg "This is a string"}
    :result     {:id          "abcd"
                 :stringField "This worked"
                 :numberField 5
                 :nullField   nil}
    :entities   {[::cache :root]
                 {:id                                             "abcd"
                  :nullField                                      nil
                  "numberField({\"intArg\":5,\"floatArg\":3.14})" 5
                  "stringField({\"arg\":\"This is a string\"})"   "This worked"
                  ::cache                                         :root}}}

   :default-vars
   {:query      (d/parse-document
                  "query someBigQuery(
                     $stringArg: String = \"This is a default string\"
                     $intArg: Int
                     $floatArg: Float
                   ) {
                     id
                     stringField(arg: $stringArg)
                     numberField(intArg: $intArg, floatArg: $floatArg)
                     nullField
                   }")
    :input-vars {:intArg   5
                 :floatArg 3.14}
    :result     {:id          "abcd"
                 :stringField "This worked"
                 :numberField 5
                 :nullField   nil}
    :entities   {[::cache :root]
                 {:id                                                   "abcd"
                  :nullField                                            nil
                  "numberField({\"intArg\":5,\"floatArg\":3.14})"       5
                  "stringField({\"arg\":\"This is a default string\"})" "This worked"
                  ::cache                                               :root}}}

   :directives
   {:query    (d/parse-document
                "{
                   id
                   firstName @include(if: true)
                   lastName @upperCase
                   birthDate @dateFormat(format: \"DD-MM-YYYY\")
                 }")
    :result   {:id        "abcd"
               :firstName "James"
               :lastName  "BOND"
               :birthDate "20-05-1940"}
    :entities {[::cache :root]
               {:id                                                  "abcd"
                :firstName                                           "James"
                "lastName@upperCase"                                 "BOND"
                "birthDate@dateFormat({\"format\":\"DD-MM-YYYY\"})"  "20-05-1940"
                ::cache                                              :root}}}})

(defn log [args]
  (.log js/console args))

(defn aliased? [selection] (:field-alias selection))
(defn has-args? [selection] (:arguments selection))
(def regular-directives #{"include" "skip"})
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
  "returns string key if selection has args otherwise return keywordized field name"
  (cond-> (:field-name selection)
          (has-args? selection) (attach-args-to-key context selection)
          (custom-dirs? selection) (attach-directive-to-key context selection)
          (not (or (has-args? selection) (custom-dirs? selection)))
          keyword))

(defn map-vals [m f]
  (into {} (for [[k v] m] [k (f v)])))

(defn combine-maps-of-seqs [list-of-maps]
  "[{:one [1] :two [2]} {:one [1]} {:three [3]}] => {:one [1 1], :two [2], :three [3]}"
  (let [m (apply (partial merge-with concat) list-of-maps)]
    (map-vals m #(if (sequential? %) % (vector %)))))

(defn weird-selection? [selection]                          ; todo: better name
  "these selections require a modification of keys in the result"
  (or (aliased? selection)
      (has-args? selection)
      (custom-dirs? selection)))

(defn find-weird-selections
  "returns a vector with the path to the selection that is 'weird' and the selection itself"
  ([selection-or-operation]
    (find-weird-selections selection-or-operation []))
  ([selection path]
   (log "find alias")
   (log {:selection selection :path path})
   (let [field-name (:field-name selection)
         current-path (if field-name (conj path field-name) path)
         pathed-selection (if (weird-selection? selection) {path selection} {})
         child-weird-selections (map #(find-weird-selections % current-path) (:selection-set selection))
         pathed-selections (conj child-weird-selections pathed-selection)]
     (log {:current-path current-path :pathed-selection pathed-selection :pathed-selections pathed-selections})
     (combine-maps-of-seqs pathed-selections))))

(defn modify-field [context result {:keys [field-alias field-name] :as selection}]
  (let [field-alias (keyword field-alias)
        field-name (keyword field-name)
        field-val (if (aliased? selection) (get result field-alias) (get result field-name))]
    (-> result
        (dissoc field-name)
        (dissoc field-alias)
        (assoc (field-key selection context) field-val))))

(defn modify-fields-reducer [context result [path weird-selections]]
  (let [modify-fields (fn [res weird-selections]
                        (reduce (partial modify-field context) res weird-selections))]
    (if (empty? path)
      (modify-fields result weird-selections)
      (update-in result path #(modify-fields % weird-selections)))))

(defn write-to-cache
  [document result {:keys [input-vars store] :or {input-vars {} store (create-store)}}]
  (let [first-op (-> document :ast :operations first)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variables first-op)}          ; info about the kinds of variables supported by this op
        root? (= (:operation-type first-op) "query")
        updated-res (if root? (assoc result ::cache :root) result)
        weird-selections (find-weird-selections first-op)
        updated-res (reduce (partial modify-fields-reducer context) updated-res weird-selections)]
    (.log js/console updated-res)
    (log "adding ^^ to store")
    (add store updated-res)))

(defn verify-aliases [k]
  (let [{:keys [query]} (get test-queries k)]
    (find-weird-selections (-> query :ast :operations first))))

(defn verify [k & no-logs?]
  (log (str "verifying results for " k))
  (let [{:keys [query input-vars result entities]} (get test-queries k)
        new-cache (write-to-cache query result {:input-vars input-vars})]
    (when-not no-logs?
      (log new-cache)
      (log "correct: ")
      (log entities)
      (log "current: ")
      (log (:entities new-cache))
      (log (= entities (:entities new-cache))))
    (= entities (:entities new-cache))))

(defn verify-all []
  (into {} (map #(vector % (verify % true))
                (keys test-queries))))

; give every entitiy a unique field that can be added to id-attr
; :cache/ROOT_VALUE.objField


;; OR (probably better)
;; add an additional field on the parent object for fields that have arguments
;; so objField and ("objField(0)") that will both have results.
;; so when multiple queries update the same object the main field will still update
;; but we'd also be able to get the specific entities from arguments
;; would probably want to use a similar stratgey for aliased fields
;; make sure to figure out what the proper field should be
;; but also store the aliased field name

;; how do you query with this strategy? get all the objects that match the root query
;; and for objects where the argument field doesn't exist you jsut return nil or a partial query