(ns artemis.stores.mapgraph.core
  (:require [artemis.stores.protocols :as sp]
            [artemis.stores.mapgraph.write :refer [write-to-cache]]
            [artemis.stores.mapgraph.query :refer [query-from-cache]]))

;; Protocol Implementation

;; This store requires that every graphql query gets sent with the __typename field.
;; It also expects that a list of primary ids for each type be given to the cache on initialization
;; IDs are namespaced keys that are formatted as such :<typename>/<primary-key-field>
;; If the necessary primary key field isn't returned in a query, the cache will store the field with a generic key
;; and it will not be retrievable via a normal look up
(defrecord MapGraphStore [id-attrs entities cache-key]
  sp/GQLStore
  (-query [this document variables return-partial?]         ;todo: implement return-partial
    (query-from-cache document variables this))
  (-write [this data document variables]
    (.log js/console data)
    (write-to-cache document variables (:data data) this)))

(defn store?  ;todo: figure out how to use this function in other namespaces without circular deps issues
  "Returns true if store is a mapgraph store."
  [store]
  (and (instance? MapGraphStore store)
       (satisfies? sp/GQLStore store)
       (set? (:id-attrs store))
       (map? (:entities store))))

(defn create-store
  ([] (create-store {}))
  ([{:keys [id-attrs entities cache-key] :or {id-attrs #{} entities {} :cache-key ::cache}}]
   {:post [(store? %)]}
   (let [cache-key (or cache-key ::cache)]
     (->MapGraphStore (conj id-attrs cache-key) entities cache-key))))

(defn clear
  "Returns a store with unique indexes and entities cleared out."
  [store]
  (assoc store :entities {} :id-attrs #{}))
