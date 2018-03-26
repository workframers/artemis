(ns artemis.stores.protocols
  (:refer-clojure :exclude [-write]))

(defprotocol GQLStore
  "Defines a protocol that can be applied on any type of GraphQL data store."
  (-read [_ document variables return-partial?]
    "Returns the result of a GraphQL query against the store. The store should
    return a map of `{:result <any>, :partial? <boolean>}`.

    - `document` is an instance of artemis.document/Document
    - `variables` is a map of variables for the query
    - `return-partial?` states if a partially fulfilled query can be returned

    If the query cannot be fulfilled at all, i.e. the data is does not exist in
    the store, or if `return-partial?` is false and the store is not able to
    fulfill the entire query, this function should return `nil`.")
  (-write [_ data document variables]
    "Writes the data resulting from a GraphQL query to the store.

    - `data` is the result received from executing a GraphQL query (ex. against a server)
    - `document` is the instance of artemis.document/Document that was run to produce `data`
    - `variables` is a map of variables for the query that was run

    Should return the updated store."))
