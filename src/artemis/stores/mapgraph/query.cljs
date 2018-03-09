(ns artemis.stores.mapgraph.query
  (:require [clojure.set :refer [rename-keys]]
            [artemis.stores.mapgraph.common :refer [like get-ref map-keys]]
            [artemis.stores.mapgraph.selections :refer [field-key aliased?]]))


(defn clear
  "Returns a store with unique indexes and entities cleared out."
  [store]
  (assoc store :entities {} :unique-indexes #{}))

(defn entity?
  "Returns true if map is an entity according to the db schema. An
  entity is a map from keywords to values with exactly one identifier
  key."
  [store map]
  (and (map? map)
       (every? keyword? (keys map))
       (= 1 (count (filter #(contains? map %) (:id-attrs store))))))

(defn ref-to
  "Returns a lookup ref for the entity using the schema in db, or nil
  if not found. The db does not need to contain the entity."
  [store entity]
  (get-ref entity (:id-attrs store)))

(defn ref?
  "Returns true if ref is a lookup ref according to the db schema."
  [store ref]
  (and (vector? ref)
       (= 2 (count ref))
       (contains? (:id-attrs store) (first ref))))

(defn modify-entity-for-gql                                 ;todo: fix how wasteful and unperformant this is
  "converts the selection's key in entity to what it would be in a normal gql response"
  [selection context ent]
  (-> ent
      (map-keys #(if (keyword? %) (-> % name keyword) %))
      (rename-keys {(field-key selection context) (-> selection :field-name keyword)})))

(defn ->gql-pull-pattern [{:keys [selection-set] :as field-or-op}]
  "this pull pattern is comprised of selections instead of keywords"
  (mapv (fn [sel]
          (let [sel (assoc sel ::selection true)]
            (if (:selection-set sel)
              {sel (->gql-pull-pattern sel)} sel)))
        selection-set))

(defn selection? [m] (::selection m))

(defn expr-and-entity-for-gql
  "if the pull fn is given a gql context, extract the expression from the selection
   within the pull pattern and modify the entity accordingly"
  [expr entity gql-context]
  (let [selection (when (selection? expr) expr)
        expr (if (and gql-context selection)
               (-> selection :field-name keyword)
               expr)
        entity (if gql-context
                 (modify-entity-for-gql selection gql-context entity)
                 entity)]
    {:expr expr :entity entity :selection selection}))

(declare pull)

(defn- pull-join
  "Executes a pull map expression on entity."
  [{:keys [entities] :as store} result pull-map entity gql-context]
  (reduce-kv
    (fn [result k join-expr]
      (let [{:keys [expr entity]} (expr-and-entity-for-gql k entity gql-context)
            k expr]
        (if (contains? entity k)
          (let [val (get entity k)]
            (cond
              (nil? val)
              (assoc result k val)

              (ref? store val)
              (assoc result k (pull store join-expr val gql-context))

              :else
              (do (when-not (coll? val)
                    (throw (ex-info "pull map pattern must be to a lookup ref or a collection of lookup refs."
                                    {:reason            ::pull-join-not-ref
                                     ::pull-map-pattern pull-map
                                     ::entity           entity
                                     ::attribute        k
                                     ::value            val})))
                  (assoc result k (like val (map #(pull store join-expr % gql-context) val))))))
          ;; no value for key
          result)))
    result
    pull-map))

(defn pull
  "Returns a map representation of the entity found at lookup ref in
  db. Builds nested maps following a pull pattern.

  A pull pattern is a vector containing any of the following forms:

     :key  If the entity contains :key, includes it in the result.
           If you pass gql-context to this fn, this key must be a
           gql selection.

     '*    (literal symbol asterisk) Includes all keys from the entity
           in the result.

     { :key sub-pattern }
           The entity's value for key is a lookup ref or collection of
           lookup refs. Expands each lookup ref to the entity it refers
           to, then applies pull to each of those entities using the
           sub-pattern.
           As with key above, if gql-context is passed in then these
           lookup refs must be gql selections."
  [{:keys [entities] :as store} pattern lookup-ref & [gql-context]]
  (when-let [entity (get entities lookup-ref)]
    (reduce
      (fn [result expr]
        (let [{:keys [expr entity selection]} (expr-and-entity-for-gql expr entity gql-context)]
          (cond
            (keyword? expr)
            (if-let [[_ val] (find entity expr)]
              (if (aliased? selection)
                (assoc result (-> selection :field-alias keyword) val)
                (assoc result expr val))
              result)

            (map? expr)
            (pull-join store result expr entity gql-context)

            (= '* expr)                                     ; don't re-merge things we already joined
            (merge result (apply dissoc entity (keys result)))

            :else
            (throw (ex-info "Invalid form in pull pattern"
                            {:reason      ::invalid-pull-form
                             ::form       expr
                             ::pattern    pattern
                             ::lookup-ref lookup-ref})))))
      {}
      pattern)))

(defn query-from-cache
  [document input-vars store]
  (let [first-op (-> document :ast :operations first)
        context {:input-vars input-vars                     ; variables given to this op
                 :vars-info (:variables first-op)           ; info about the kinds of variables supported by this op
                 :store store}
        pull-pattern (->gql-pull-pattern first-op)]
    (pull store pull-pattern [(:cache-key store) :root] context)))
