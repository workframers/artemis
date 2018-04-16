(ns artemis.stores.protocols
  (:refer-clojure :exclude [-write]))

(defprotocol
  ^{:added "0.1.0"}
  GQLStore
  "Defines a protocol that can be applied on any type of GraphQL data store."
  (-read [this document variables return-partial?]
    "Returns the result of a GraphQL query against the store. The store should
    return a map of `{:data <any>, :partial? <boolean>}`.

    - `document`        An `artemis.document/Document`
    - `variables`       A map of variables for the query
    - `return-partial?` States if a partially fulfilled query can be returned

    If the query cannot be fulfilled at all, i.e. the data does not exist in
    the store, or if `return-partial?` is `false` and the store is not able to
    fulfill the entire query, should return `nil`.")
  (-write [this data document variables]
    "Writes the data resulting from a GraphQL query to the store.

    - `data`      The result received from executing a GraphQL query (ex.
                   against a server)
    - `document`  The `artemis.document/Document` that was run to produce `data`
    - `variables` A map of variables for the query that was run

    Should return the updated store."))
