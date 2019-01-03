(ns artemis.stores.mapgraph.read
  (:require [clojure.set :refer [rename-keys]]
            [artemis.stores.mapgraph.common :refer [like get-ref map-keys fragments-map]]
            [artemis.stores.mapgraph.selections :as sel :refer [field-key aliased? ref-join-expr]]))

(defn- entity?
  "Returns true if map is an entity. An entity is a map from keywords to values
  with an existing id according to the db id-fn."
  [{:keys [id-fn]} map]
  (and (map? map)
       (not (sorted? map))
       (every? keyword? (keys map))
       (some? (id-fn map))))

(defn- ref?
  "Returns true if ref is a lookup ref."
  [store ref]
  (boolean (:artemis.mapgraph/ref ref)))

(defn- ^boolean incomplete?
  "Returns true if the value is impartial"
  [x]
  (keyword-identical? x ::incomplete-value))

(defn- modify-entity-for-gql                                 ;todo: fix how wasteful and unperformant this is
  "Converts the selection's key in entity to what it would be in a normal gql response"
  [selection context ent]
  (-> ent
      (map-keys #(if (keyword? %) (-> % name keyword) %))
      (rename-keys {(field-key selection context) (-> selection :field-name keyword)})))

(defn- ->gql-pull-pattern [{:keys [selection-set] :as field-or-op} fragments]
  "Returns a pull pattern comprised of selections instead of keywords"
  (->> selection-set
       (sel/resolve-fragments fragments)
       (mapv (fn [sel]
               (let [sel (assoc sel ::selection true)]
                 (if (:selection-set sel)
                   {sel (->gql-pull-pattern sel fragments)} sel))))))

(defn- selection? [m] (::selection m))

(defn- expr-and-entity-for-gql
  "When the pull fn is given a gql context, extract the expression from the selection
   within the pull pattern and modify the entity such that it's keys are formatted the
   way they would have been in a graphql query response.
   If no gql context, return the expression and entity as is with a nil selection."
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
      (let [{:keys [expr entity selection]} (expr-and-entity-for-gql k entity gql-context)
            k expr]
        (if (contains? entity k)
          (let [val (get entity k)]
            (cond
              (nil? val)
              (assoc result k val)

              (ref? store val)
              (let [pulled (pull store
                                 (ref-join-expr entities join-expr (get entity expr) selection)
                                 val
                                 gql-context)]
                (if (incomplete? pulled)
                  (reduced pulled)
                  (assoc result k pulled)))

              :else
              (do (when-not (coll? val)
                    (throw (ex-info "pull map pattern must be to a lookup ref or a collection of lookup refs."
                                    {:reason            ::pull-join-not-ref
                                     ::pull-map-pattern pull-map
                                     ::entity           entity
                                     ::attribute        k
                                     ::value            val})))
                  (assoc result k (like val (map #(pull store
                                                        (ref-join-expr entities join-expr % selection)
                                                        %
                                                        gql-context)
                                                 val))))))
          (if-let [redirect (some-> gql-context
                                    :store
                                    :cache-redirects
                                    (get k)
                                    (apply [{:store (:store store gql-context)
                                             :parent-entity entity
                                             :variables (:input-vars gql-context)}]))]
            ;; cache-redirect found
            (let [redirected-pull (pull (:store gql-context)
                                        (ref-join-expr entities join-expr (get entity expr) selection)
                                        {:artemis.mapgraph/ref redirect}
                                        gql-context)]
              (if redirected-pull
                (assoc result k redirected-pull)
                ;; redirect root resulted in nothing
                result))
            (if (not (:return-partial? gql-context))
              ;; no value found and don't return partial
              (reduced ::incomplete-value)
              ;; no value for key in store and no cache-redirect found
              (assoc result k ::incomplete-value))))))
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
           sub-pattern.

  ~~ For devs working on the internals ~~
     If you pass in a gql-context, the keys and refs in the pull pattern
     must all be gql selections from the generated ast. There's no support for
     handling pull patterns that are combination of selections and normal keys"
  [{:keys [entities] :as store} pattern lookup-ref & [gql-context]]
  (let [ref (:artemis.mapgraph/ref lookup-ref)]
    (if (or (nil? ref) (contains? entities ref))
      (when-let [entity (get entities (:artemis.mapgraph/ref lookup-ref))]
        (reduce
         (fn [result expr]
           (let [{:keys [expr entity selection]} (expr-and-entity-for-gql expr entity gql-context)]
             (cond
               (keyword? expr)
               (let [k (if (aliased? selection)
                         (-> selection :name keyword)
                         expr)]
                 (if-let [[_ val] (find entity expr)]
                   (assoc result k val)
                   ;; Keyword was in query, but not found in store
                   (if (:return-partial? gql-context)
                     (assoc result k ::incomplete-value)
                     (reduced ::incomplete-value))))

               (map? expr)
               (let [map-result (pull-join store result expr entity gql-context)]
                 (if (incomplete? map-result)
                   (reduced map-result)
                   map-result))

               (= '* expr)                                  ; don't re-merge things we already joined
               (merge result (apply dissoc entity (keys result)))

               :else
               (throw (ex-info "Invalid form in pull pattern"
                               {:reason      ::invalid-pull-form
                                ::form       expr
                                ::pattern    pattern
                                ::lookup-ref lookup-ref})))))
         {}
         pattern))
      ::incomplete-value)))

(defn incomplete-or-has-incomplete? [val]
  (or (incomplete? val)
      (and (coll? val)
           (not-empty (filter incomplete? val)))))

(defn remove-incomplete [val]
  (cond
    (map? val)
    (->> val
         (remove (fn [[k v]] (incomplete-or-has-incomplete? v)))
         (map (fn [[k v]] [k (remove-incomplete v)]))
         (into {}))

    (coll? val)
    (like val (map remove-incomplete val))

    :else
    (when-not (incomplete? val)
      val)))

(defn has-incomplete? [val]
  (boolean
   (cond
     (map? val)
     (not-empty (filter (fn [[k v]] (has-incomplete? v)) val))

     (coll? val)
     (not-empty (filter has-incomplete? val))

     :else
     (incomplete? val))))

(defn read-from-cache
  [document input-vars store return-partial?]
  (let [first-op (-> document :operation-definitions first)
        fragments (fragments-map document)
        context {:input-vars input-vars                      ; variables given to this op
                 :vars-info (:variable-definitions first-op) ; info about the kinds of variables supported by this op
                 :return-partial? return-partial?
                 :store store}
        pull-pattern (->gql-pull-pattern first-op fragments)
        result (pull store pull-pattern {:artemis.mapgraph/ref "root"} context)
        partial? (has-incomplete? result)]
    {:data (if return-partial?
             (remove-incomplete result)
             (when-not partial?
               (not-empty result)))
     :partial? partial?}))

(defn read-from-entity
  [document ent-ref input-vars store return-partial?]
  (let [first-frag (-> document :fragment-definitions first)
        fragments (fragments-map document)
        context {:input-vars      input-vars
                 :return-partial? return-partial?
                 :store           store}
        pull-pattern (->gql-pull-pattern first-frag fragments)
        result (pull store pull-pattern {:artemis.mapgraph/ref ent-ref} context)
        partial? (has-incomplete? result)]
    {:data (if return-partial?
             (remove-incomplete result)
             (when-not partial?
               (not-empty result)))
     :partial? partial?}))
