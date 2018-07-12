# Direct Cache Access

To interact directly with your cache, you can use the the core `read`, `write`,
`read-fragment`, and `write-fragment` functions.

## read-query

When you call `read` on an Artemis store, it'll attempt to synchronously
resolve the query against it's cache. It's used internally by the `query!` and
`mutate!` functions, but it's also available for public use. You call `read`
with a store, a query document, and a map of variables:

```clojure
(require '[artemis.core :as a]
         '[artemis.document :refer [parse-document]])

(def get-users-doc
  (parse-document
    "query getUsers {
       allUsers {
         id
         name
       }
     }"))

(a/read (a/store client) get-users-doc {})
```

Keep in mind that if a query hasn't been resolved remotely, its result won't
yet exist in the cache, so calling `read` with a query that hasn't ever before
been executed will result in `nil`.

_If you've created cache redirects for fields, you can actually redirect the
store to resolve a query that hasn't been executed before. Read more about
cache redirects in the previous topic._

## write-query

Just you like you can read a query result from the cache, you can also write
a new query result to the cache by using the `write` function. Writing will
allow you to change data in your local cache. You call `write` with a store,
data, a query document, and a map of variables:

```clojure
(require '[artemis.core :as a]
         '[artemis.document :refer [parse-document]])

(def get-users-doc
  (parse-document
    "query getUsers {
       allUsers {
         id
         name
       }
     }"))

(a/write (a/store client)
         {:data {:allUsers [...]}}
         get-users-doc
         {})
```

## read-fragment and write-fragment
`read-fragment` and `write-fragment` work just like regular `read` and `write`,
but they allow you to read/write based on a fragment selection for a specific
entity.

`read-fragment` is called with a store, a fragment, and a reference to an
entity (i.e. the key it's being normalized on):

```clojure
(require '[artemis.core :as a]
         '[artemis.document :refer [parse-document]])

(def user-fragment
  (parse-document
    "fragment MyPerson on Person {
       id
       name
     }"))

;; Read id and name for user 1
(a/read-fragment (a/store client)
                 user-fragment
                 1)
```

`write-fragment` is called with a store, data, a fragment, and a reference to
an entity (i.e. the key it's being normalized on):

```clojure
(require '[artemis.core :as a]
         '[artemis.document :refer [parse-document]])

(def user-fragment
  (parse-document
    "fragment MyPerson on Person {
       name
     }"))

;; Write name for user 1
(a/write-fragment (a/store client)
                  {:data {:name "No Longer Bob"}}
                  user-fragment
                  1)
```

## Reading and Writing Together

In some scenarios you might want to combine reading and writing. For example,
you might do so in order to adda new item to a list fetched from the server:

```clojure
(defn add-user [store new-user]
  (let [current-users(-> store
                         (a/read get-users-doc {})
                         (get-in [:data :allUsers])
                         not-empty)]
    (a/write store
             {:data {:allUsers (conj current-users new-user)}}
             get-users-doc
             {})))
```
