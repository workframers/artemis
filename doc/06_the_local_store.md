# The Local Store

When we create our Artemis client we usually pass a `:store` option. The store
is a local cache of the results of GraphQL queries. Depending on the fetch
policy we set, Artemis might try to use the cache to resolve the data being
queried. Let's look at what a store is.

## Creating a Store

The `GQLStore` protocol is an abstraction defining a local store that we can
locally execute operations against.  Each store implements `-read`,`-write`,
`-read-fragment`, and `-write-fragment` functions, which are, respectively,
called by `artemis.core/read`, `artemis.core/write`, `artemis.core/read`, and
`artemis.core/write`. Our client will call each of these functions on the store
at specific times, expecting that the store has correctly implemented them.

## Reading and Writing

When we define our store, the `-read` implementation will be called with the
parsed GraphQL document, the variables map, and a boolean `return-partial?` as
arguments. Return partial is specified at query-time by the executing code and
states whether or a partially fulfilled query (i.e. a query that might return
values for only some fields instead of all) is accepted.

The `-read` implementation should use these three arguments to attempt to
fulfill a query (either a fully or partially) and return the result. If the
store isn't able to fulfill the query, it should return `nil`.

The `-write` implementation is responsible for taking some data and writing it
to a local cache, then returning the updated store.

`-read-fragment` and `-write-fragment` work pretty much the same way, but also
take an argument that is a reference to a particular node we want to update.
What that reference looks like is up to the store's implementation.

As long as you implement these four functions you can build any kind of store
you'd like -- DataScript, IndexedDB, Local Storage, whatever you want --
Artemis will call those functions with the right information at the right
times.

Check out the [API docs](./artemis.stores.protocols.html) for more on creating
your own store.

## Mapgraph Store

While you can build your own `GQLStore`, it can potentially be complicated to
implement, so Artemis comes with a default store built atop
[Mapgraph](https://github.com/stuartsierra/mapgraph).

The default store presents a normalized, in-memory database of linked entities.
All entities are flatly-stored based on a reference value; nested entities are
replaced with a reference lookup.

We can specify the reference values for our entities by passing a `:id-fn` when
creating our store. For example, to use each entity's `:id` as the lookup we
can do:

```clojure
(create-store :id-fn (fn [entity] (:id entity)))
```

It's important to note that lookups should be unique across entities, so make
sure you're `id-fn` returns a value that is unique to the entity it's passed.
One approach is to use UIDs. It's also common to prefix the ID with the
entities `__typename` value. Here's an example of the second approach:

```clojure
(create-store :id-fn (fn [entity] (str (:__typename entity) (:id entity))))
```

_The default `:id-fn` is `:id`, so if the ID value is unique across all of your
entities, you may not even need to specify an `:id-fn`._

### Automatic Cache Updates

Because our entities are normalized, we often times get correct cache updates
for free after we execute queries and mutations. Let’s say we perform the
following query:

```graphql
{
  post(id: 1) {
    id
    score
  }
}
```

Then we execute a mutation:

```graphql
mutation {
  upvotePost(id: 1) {
    id
    score
  }
}
```
The ID value on both results matches up, so the score field will automatically
be updated across our entire UI.

### Cache Redirects
In some cases, a query requests data that already exists in the store under a
different key. A very common example of this is when your UI has a list
view and a detail view that both use the same data. The list view might run the
following query:

```graphql
{
  books {
    id
    title
    abstract
  }
}
```

When a specific book is selected, the detail view displays an individual item
using this query:

```graphql
{
  book(id: $id) {
    id
    title
    abstract
  }
}
```

We know that the data is already in the client cache, but because it's been
requested as part of a different query, the store doesn't know that. In order
to tell the store where to look for the data, we can point it in the right
direction using cache redirects.

When creating our store we can supply a map of redirects via the
`:cache-redirects` option. Each key in the map is a field name that we want
to redirect whenever the store can't resolve a result, and the value to the key
is a function that returns a the reference we want to be redirected to. The
function will be called with a map that contains the following:

```clojure
{:store         <the client's store>
 :parent-entity <the parent of entity for the field we're on>
 :variables     <the map of GraphQL variables>}
```

Assuming that our store is normalizing on the `:id` field, our book entity
would be stored by ID. With that said, let's take our example above and
implement a cache redirect for the `book` node:

```clojure
(def cache-redirects
  {:book (fn [{:keys [variables]}]
           (:id variables))})

(create-store :cache-redirects cache-redirects)
```

What we've done above is tell our store that if we're not able to resolve the
`book` field on any query, run the redirect function specified (which returns
the `:id` variables we're querying with) and try to query the selection set
for book from that point in the cache.

### Partial Returns
Sometimes it's useful to display partially available data while waiting for
the remaining data to be loaded. For example, you might query for a list of
books using:

```graphql
{
  books {
    id
    title
  }
}
```

If the user clicks on a specific book, you want to get more information for
that book:

```graphql
{
  book(id: $id) {
    id
    title
    abstract
    author {
      id
      firstName
      lastName
    }
  }
}
```

Depending on how your UI is designed, it might be ok to start rendering the
title of the book (since it's already been cached) while the remaining
information is being fetched.

By default, our store will only return data for a query that it's able to
fulfill entirely. You can, however, pass a `:return-partial? true` option
when reading data:

```clojure
(a/query! client book-doc {:id 1} :return-partial? true)
```

You can also pass `:return-partial?` when using `read` and `read-fragment`:

```clojure
(a/read store book-doc {:id 1} :return-partial? true)
(a/read-fragment store book-fragment-doc 1 :return-partial? true)
```

### Clearing the Store
In some cases you may want to clear your Mapgraph store. You can easily do this
by calling `artemis.stores.mapgraph.core/clear`. The `clear` function will
clear out all cached entities.

### Serializing the Cache
The entities stored in the Mapgraph cache are just regular Clojure data, so we
can easily serialize it by calling `pr-str`.

```clojure
(pr-str (:entities (a/store client)))
```

If we wanted to store our entities to localStorage everytime our store changes,
for example, we can use `pr-str` in combination with the `watch-store`
function:

```clojure
(a/watch-store client
              (fn [old-store new-store]
                (.setItem js/localStorage
                          "app-entities"
                          (pr-str (:entities new-store)))))
```

Then we could hydrate our cache by passing in our entities at bootstrap:

```clojure
(create-store :entities (or (.getItem js/localStorage "app-entities") {}))
```

_If you're going to persist the cache on every update, it would be wise to
debounce your function. The Google Closure library that comes within ClojureScript
provides a nice option via the `goog.async.Debouncer` class._
