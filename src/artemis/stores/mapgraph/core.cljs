(ns artemis.stores.mapgraph.core
  (:require [artemis.stores.protocols :as sp]
            [artemis.stores.mapgraph.write :refer [write-to-cache write-to-entity]]
            [artemis.stores.mapgraph.read :refer [read-from-cache read-from-entity]]))

(defrecord
  ^{:added "0.1.0"
    :doc   "This store requires that every entity meant to normalize for a
           GraphQL query resolves a unique value when called against the
           store's `:id-fn`."}
  MapGraphStore
  [id-fn entities cache-redirects cache-key]
  sp/GQLStore
  (-read [this document variables return-partial?]
    {:data (not-empty (read-from-cache document variables this return-partial?))})
  (-read-fragment [this document entity-ref return-partial?]
    {:data (not-empty (read-from-entity document entity-ref this return-partial?))})
  (-write [this data document variables]
    (if-let [gql-response (:data data)]
      (write-to-cache document variables gql-response this)
      this))
  (-write-fragment [this data document entity-ref]
    (if (seq data)
      (write-to-entity document (:data data) entity-ref this)
      this)))

(defn store?  ;todo: figure out how to use this function in other namespaces without circular deps issues
  "Returns `true` if `store` is a mapgraph store."
  {:added "0.1.0"}
  [store]
  (and (instance? MapGraphStore store)
       (satisfies? sp/GQLStore store)
       (fn? (:id-fn store))
       (map? (:entities store))))

(defn create-store
  "Returns a new `MapGraphStore` for the given parameters:

  - `:id-fn`           A function that will be passed an entity and should
                       return a unique value that will be used as a reference
                       to that entity. Defaults to `:id`.
  - `:entities`        A map of stored entities. Defaults to `{}`.
  - `:cache-redirects` A map of of field to function, that redirects the root
                       from whence the selection-set will be resolved. Defaults
                       to `{}`.
  - `:cache-key`       The default generic key for the store's cache. Defaults
                       to `:artemis.stores.mapgrah.core/cache`."
  {:added "0.1.0"}
  [& {:keys [id-fn entities cache-redirects cache-key]
      :or   {id-fn           :id
             entities        {}
             cache-redirects {}
             cache-key       ::cache}}]
  (MapGraphStore. id-fn entities cache-redirects cache-key))

(defn clear
  "Returns a store with entities cleared out."
  {:added "0.1.0"}
  [store]
  (assoc store :entities {}))
