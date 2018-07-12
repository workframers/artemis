# Basics

Learn how to quickly get started with Artemis.

## Installation

Artemis is available on [Clojars](https://clojars.org/com.workframe/artemis).

Include it in your `project.clj` or `deps.edn` dependencies.

## Preface

Once you've installed Artemis, you'll need a GraphQL server to make use of it.
We've set-up a publicly accessible [Graphcool](https://www.graph.cool/) server
that you can use if you'd like to follow along verbatim. You can, of course,
also use your own server; the ideas will transfer over.

## Getting Started

In your ClojureSript project, start off by creating a network-chain, store, and
client. We'll cover each of these in more depth in later sections, including
how to customize each, but for now you can use the built-in versions.

```clojure
(require '[artemis.core :as a]
         '[artemis.network-steps.http :as http]
         '[artemis.stores.mapgraph.core :as mgs])

(def graphcool-url "https://api.graph.cool/simple/v1/cjjh9nmy118fs0127i5t71oxe")

(def network-chain (http/create-network-step graphcool-url))

(def store (mgs/create-store))

(def client (a/create-client :network-chain network-chain
                             :store         store))
```

_You can create a client without specifying either store or network chain. By
default, it'll use the same http-based network chain, but with `/graphql` set
as the server endpoint. It'll also use the same Mapgraph-based store (we'll
touch on Mapgraph later)._

## Querying

You're now ready to start executing GraphQL queries. To fire off your first
query, create a GraphQL document and call `query!`.

```clojure
(require '[artemis.document :refer [parse-document]])

(def get-jerry
  (parse-document
   "query getJerry {
      User(id: \"cjjh9x0q97x7r0111osr3t352\") {
        id
        name
      }
    }"))

(a/query! client get-jerry :fetch-policy :remote-only)
```

_You might have noticed that we're passing a `:fetch-policy` option. Don't
worry about that for now, it'll be covered in a later topic._

If you inspect network traffic using your browser's devtools, you should see a
request to the GraphQL server and a response that looks something like this:

```json
{
  "data": {
    "User": {
      "id": "cjjh9x0q97x7r0111osr3t352",
      "name": "Jerry Seinfeld"
    }
  }
}
```

At this point, you probably want to access that data, and that brings us to
the main design pattern that Artemis is based around: using channels to manage
the asynchronous nature of querying a GraphQL source. Every call to `query!`
will return a channel that will receive messages it with information about your
GraphQL query.

Let's bring in [core.async](https://github.com/clojure/core.async/), then use
`go` to take off our channel.

```clojure
(require-macros '[cljs.core.async.macros :refer [go go-loop]])
(require '[cljs.core.async :refer [<!]])

(go (let [x (<! (a/query! client get-jerry :fetch-policy :remote-only))]
      (.log js/console x)))
```

You should see the following logged to your browser's console:

```clojure
{:data nil,
 :variables nil,
 :in-flight? true,
 :network-status :fetching}
```

Hmm, notice that data is `nil`. That can't be right -- we saw that data come
through on our network tab. The reason the data appears as `nil` is because our
channel actually receives multiple messages throughout the lifetime of the
query, each message providing information about what state the query is
currently in. To read multiple messages, let's use `go-loop` instead.

```clojure
(let [get-jerry-chan (a/query! client get-jerry :fetch-policy :remote-only)]
  (go-loop []
    (when-let [x (<! get-jerry-chan)]
      (.log js/console x)
      (recur))))
```

You should now see two log statemens, the first which is identical to what we
previously saw, and the second which is something like:

```clojure
{:in-flight? false,
 :variables nil,
 :network-status :ready,
 :data {:User {:id "cjjh9x0q97x7r0111osr3t352",
               :name "Jerry Seinfeld"}}}
```

If we inspect each of the messages, it should be apparent what each tells us
about the state of our query at the time the message was put onto the channel.

The first message let's us know that we don't have any data available, and
that's because our network status is currently `:fetching`, meaning we're
getting the data. You'll also notice that `:in-flight?` is `true` for the first
message, so we know our query is currently over the wire. The second message,
of course, shows different values for these keys.

We can use this kind of information per-message to easily render loading
indicators, delay transitions, or make other query state-based decisions.

Channels, it turns out, are a nice, easy to understand way to track ordered
updates on the state of our data fetching and manipulation. This simple design
becomes especially useful as our application grows and we're executing multiple
queries and potentially even doing so across multiple sources, such as a local
cache and a server.

_You might notice that we're using `recur` within our `go-loop`. We can safely
do this without ending up in an infinte loop because Artemis automatically
closes the channels returned by its core functions at the right time._

## Mutating

Now that we've learned how to query for data with Artemis, let's take a look
at updating data.

Let's start off by creating a mutation document:

```clojure
(def create-newman
  (parse-document
   "mutation createNewman {
      createUser(name: \"Newman\") {
        id
        name
      }
    }"))
```
Now, just like with `query!`, we pass our document to the `mutate!`. Like it's
counterpart function, `mutate!` also returns a channel, so we'll want to take
messages off it.

```clojure
(let [create-newman-chan (a/mutate! client create-newman)]
  (go-loop []
    (when-let [x (<! create-newman-chan)]
      (.log js/console x)
      (recur))))
```

Because, GraphQL mutations also include the desired return data, the messages
that Artemis puts on your channel are pretty much the same as those you'll get
when a running a query -- you'll have access to the returned `:data`, any
`:errors`, as well as meta-data, like `:in-flight?` and `:network-status`.

That's about all it takes to execute GraphQL mutations with Artemis.

## Complete Example
```clojure
(ns app.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.document :refer [parse-document]]
            [artemis.network-steps.http :as http]
            [artemis.stores.mapgraph.core :as mgs]))

;;; Set-up

(def graphcool-url "https://api.graph.cool/simple/v1/cjjh9nmy118fs0127i5t71oxe")

(def network-chain (http/create-network-step graphcool-url))

(def store (mgs/create-store))

(def client (a/create-client :network-chain network-chain
                             :store         store))

;;; Query

(def get-jerry
  (parse-document
   "query getJerry {
      User(id: \"cjjh9x0q97x7r0111osr3t352\") {
        id
        name
      }
    }"))

(let [get-jerry-chan (a/query! client get-jerry :fetch-policy :remote-only)]
  (go-loop []
    (when-let [x (<! get-jerry-chan)]
      (.log js/console x)
      (recur))))

;;; Mutation

(def create-newman
  (parse-document
   "mutation createNewman {
      createUser(name: \"Newman\") {
        id
        name
      }
    }"))

(let [create-newman-chan (a/mutate! client create-newman)]
  (go-loop []
    (when-let [x (<! create-newman-chan)]
      (.log js/console x)
      (recur))))
```
