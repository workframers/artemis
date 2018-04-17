# Getting Started

_We'll be using [swapi-graphql](https://github.com/graphql/swapi-graphql)
to run through this guide. If you'd like to follow along verbatim, it's
recommended that you clone the repo and start the server. Remember to update
the server's CORS config to work with your CLJS app.
**Access-Control-Allow-Origin** should be set to your app's URL and
**Access-Control-Allow-Credentials** should be set to true_.

Start off by creating a new Artemis client.

```clojure
(require '[artemis.core :as a])

(def client (a/create-client))
```

By default, this will make requests to the `/graphql` URI and will store our
results in an in-memory normalized store. Implementation-wise, the store is
built atop Stuart Sierra's
[Mapgraph](https://github.com/stuartsierra/mapgraph).

The `/graphql` URI may not work for everyone. We can configure it by creating
an HTTP-based network-chain that we pass to `create-client`. We'll go over what
network-chains are in another guide, but for now let's use the basic HTTP
network step that Artemis ships with to build a single-step chain. When you
start the SWAPI server, it'll tell you where it lives, but for demo purposes,
let's assume it's at `http://localhost:12345`.

```clojure
(require '[artemis.network-steps.http :as http])

(def net-chain (http/create-network-step "http://localhost:12345/"))
(def client (a/create-client :network-chain net-chain))
```

Now, when we execute queries and mutations, they'll be sent to the correct URI.

To fire off your first query, create a GraphQL document and call `query!`.

```clojure
(require '[artemis.document :refer [parse-document]])

(def yavin-iv-info (parse-document "{ planet(id: \"cGxhbmV0czoz\") { id name } }"))

(a/query! client
          yavin-iv-info
          :fetch-policy :remote-only)
```

If you inspect network traffic using your browser's devtools, you should see a
request to the GraphQL server and a response that looks like this:

```json
{"data":{"planet":{"id":"cGxhbmV0czoz","name":"Yavin IV"}}
```

Artemis should work just fine with any view library, but we'll be using Reagent
in the next guide to demonstrate how you can use the Artemis client to display
GraphQL queried data on your page.
