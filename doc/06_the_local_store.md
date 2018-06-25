# The Local Store

Until now we've been making all of our queries with the `:fetch-policy` set to
`:remote-only`. What the heck is a fetch policy anyways? Well, a fetch policy
tells our client how we want it to make queries in regards to our remote
GraphQL server and the local store we specify when creating our client.

Just like we set a `:network-chain` option when we create our client, we can
also set a `:store` option. For all queries where the fetch policy is anything
but `:remote-only`, our client will attempt to query the local store instead
of, or in addition to our server. Before going further into fetch policies,
let's look at what a store is.

## Creating a Store

The `GQLStore` protocol is an abstraction defining a local store where that we
can locally execute operations against. As you're probably aware, local stores
allow us to improve the user experience by creating an app that feels snappier.
Each store implements `-read` and `-write` functions, which are, respectively,
called by `artemis.core/read` and `artemis.core/write`. Our client will call
each of `read` and `write` on the store at specific times, expecting that the
store has correctly implemented them.

### Reading and Writing

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

As long as you implement both `-read` and `-write` you can build any kind of
store you'd like -- DataScript, IndexedDB, Local Storage, whatever you want --
Artemis will call those functions with the right information at the right
times.

Check out the [API docs](./artemis.stores.protocols.html) for more on implement
reading and writing.

## Mapgraph Store

While you can build your own `GQLStore`, it can potentially be complicated to
implement, so Artemis comes with a default store built atop
[Mapgraph](https://github.com/stuartsierra/mapgraph).

By using Mapgraph, the default store presents a normalized, in-memory database
of linked entities. We can link those entities by specifying attributes to
normalize on. We use the `:id-attrs` option to do so:

```clojure
(require '[artemis.core :as a]
         '[artemis.stores.mapgraph.core :as mgs])

(def mapgraph-store (mgs/create-store :id-attrs #{:Planet/id}))
(def client (a/create-client :store mapgraph-store))
```

`:id-attrs` is a set of namespaced keywords formatted
`:<typename>/<primary-key-field>`. The typename is the value for a particular
entity's `__typename` in a GraphQL query. That means that when using the
Mapgraph store, in order to get the benefits of normalization, we need to set
the keyword we want to normalize on and include the `__typename` field when
querying for those entities. The planet example we've been using so far would
look like this:

```clojure
(require '[artemis.core :as a]
         '[artemis.document :refer [parse-document]]
         '[artemis.stores.mapgraph.core :as mgs])

(def mapgraph-store (mgs/create-store :id-attrs #{:Planet/id}))
(def client (a/create-client :store mapgraph-store))

(def planet-info
  (parse-document
   "query planetInfo($id:ID!) {
     planet(id:$id) {
       __typename
       id
       name
     }
   }"))
```

Adding `__typename` to every selection set would get tedious. Artemis, however,
automatically includes the `__typename` field when querying, so we'll just
have to tell it what typenames we want to normalize on in order to correctly
store our results in our Mapgraph store. With that done, we can switch our
fetch policy to take advantage of this.

Let's move back to our application code and set planet ID to a primary key,
then set the fetch policy to `:local-then-remote`. For reference, here's all
the code:

```clojure
(ns my-app.view
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.network-steps.http :as http]
            [artemis.stores.mapgraph.core :as mgs]
            [artemis.document :refer [parse-document]]
            [reagent.core :as r]
            [cljs.core.async :refer [<!]]))

(def gql-result (reagent/atom []))
(def net-chain (http/create-network-step "http://localhost:12345/"))
(def mg-store (mgs/create-store :id-attrs #{:Planet/id}))
(def client (a/create-client :network-chain net-chain
                             :store mg-store)) ;; Add the store here!

(def planet-info
  (parse-document
   "query planetInfo($id:ID!) {
     planet(id:$id) {
       id
       name
      }
    }"))

(defn my-app []
  (let [message (last @gql-result)]
    [:div
     (if (and (nil? (:data message)) (:in-flight? message))
       "Loading..."
       (if-let [errors (:errors message)]
         [:div
          "Something went wrong!"
          [:ul
           (for [e errors]
             [:li {:key (hash e)}
              (str e)])]]
         [:div
          "The planet is: "
          (-> message :data :planet :name)]))]))

(let [planet-info-chan (a/query! client
                                 planet-info
                                 {:id "cGxhbmV0czox"}
                                 :fetch-policy :local-then-remote)] ;; This part changed!
  (go-loop []
    (when-let [r (<! planet-info-chan)]
      (swap! gql-result conj r)
      (recur))))

(defn ^:export main []
  (r/render-component [my-app]
                      (.getElementById js/document "app")))
```

If we refresh our browser, everything should feel the same. But, under the hood
the Mapgraph store has stored the Tatooine planet as a normalized entity based
off of the ID `"cGxhbmV0czox"`. This means the any time we get a GraphQL result
that references a planet with the ID `"cGxhbmV0czox"` it will be reconciled
with the values we've already got at the primary key.

Additionally, we can now run queries against that local store. In fact, we're
already doing that by changing our fetch policy to `:local-then-remote`, but
we'll get to that in a second.

Add this to bit of temporary code to your application:

```clojure
(defn ^:export local-query! []
  (let [c (a/query! client
                    planet-info
                    {:id "cGxhbmV0czox"}
                    :fetch-policy :local-only)]
    (go (let [x (<! c)]
          (.log js/console x))))) ;; If you don't have Clojure printing tools installed,
                                  ;; you may want to wrap `x` in `(clj->js)`

```

Now, in your browser invoke `my_app.view.local_query_BANG_()`. You should see
a message printed to your console. If you inspect that message, you'll see that
the `:planet` value for our `:data` key is Tatooine, as to be expected. However,
you'll see that the `:in-flight?` value is `false` and the `:network-status` is
`:ready`. Open up your network tab and re-run the same function; no query is
sent over the wire, yet we're able to successfully satisfy our GraphQL query --
all from the local Mapgraph store! This happens because in our `local-query!`
function we've set the fetch policy to `:local-only`, so the Artemis client
will only try to run the query against the local store.

Fetch policies allow us to tell the client how and where we want it to execute
our queries. We can choose the right policy depending on our application's
needs. For example, if we have some data that we absolutely know is cached in
our local store and is highly unlikely to change, we may want to save on
bandwidth by setting the policy to `:local-only`. Conversely, if we have
something that is highly likely to change we may want to use `:remote-only`,
meaning the client will skip the local store and always go directly to the
server. Again, Artemis allows you to choose the right policy for the job.

You can go ahead and delete the temporary function we wrote, or continue
playing around with different fetch policies. They're enumerated in the final
section below.

## Mutations Against the Local Store

Whenever we run `mutate!`, Artemis will call `write` on the local store,
passing it the mutation document. This allows the store to stay up-to-date with
what's going on remotely. By default, Artemis will only attempt to write to the
local store after a successful remote write. If you'd like the store to perform
its write optimistically, you can pass a value to the `:optimistic-result`
option when calling `mutate!`. You can read more about that in the next guide.

## Fetch Policies

#### `:local-only`
A query will never be executed remotely. Instead, the query will only run
against the local store. If the query can't be satisfied locally, an error
message will be put on the return channel. This fetch policy allows you to only
interact with data in your local store without making any network requests
which keeps your component fast, but means your local data might not be
consistent with what is on the server. For this reason, this policy should only
be used on data that is highly unlikely to change, or is regularly being
refreshed.

#### `:local-first`
Will run a query against the local store first. The result of the local query
will be put on the return channel. If that result is a non-nil value, then a
remote query will not be executed. If the result is `nil`, meaning the data
isn't available locally, a remote query will be executed. This fetch policy
aims to minimize the number of network requests sent. The same cautions around
stale data that applied to the `:local-only` policy do so for this policy as
well.

#### `:local-then-remote`
Like the `:local-first` policy, this will run a query against the local store
first and put the result on the return channel.  However, unlike
`:local-first`, a remote query will always be executed regardless of the value
of the local result. This fetch policy optimizes for users getting a quick
response while also trying to keep cached data consistent with your remote data
at the cost of extra network requests.

#### `:remote-only`
This fetch policy will never run against the local store.  Instead, it will
always execute a remote query. This policy optimizes for data consistency with
the server, but at the cost of an instant response.
