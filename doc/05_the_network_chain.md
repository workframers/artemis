# The Network Chain

All Artemis queries require a client and all clients require a network chain.
Here's what a network chain is and how you use it.

When you call `query!` or `mutate!`, Artemis uses a chain of steps that let you
configure your network requests. We call it a "network chain", and it's made up
of one or more "network steps".

A network step represents some action between calling `query!` or `mutate!` and
sending the request to your GraphQL server.  For example, a network step might
send the request via an HTTP client or WebSocket connection, or it could do
something like log the request to the console or add an auth token.

## Creating a Network Chain

The `GQLNetworkStep` protocol is an abstraction for defining a valid network
step. Each network step implements an `-exec` function, which is called by
`artemis.core/exec`.

For example, here's a basic logging step:

```clojure
(require '[artemis.network-steps.protocols :refer [GQLNetworkStep]])

(reify
  GQLNetworkStep
  (-exec [_ operation _]
    (.log js/console operation)))
```

The above example is pretty useless, however, because it's just logging our
operation (query/mutation). And, in fact, it's invalid according the spec
defined within Artemis. A network step should return -- you guessed it -- a
core.async channel. Ultimately, that channel stands as the representation of
things happening over the network.

Let's update our example, then:

```clojure
(require '[artemis.network-steps.protocols :refer [GQLNetworkStep]]
         '[cljs.core.async :refer [chan]])

(reify
  GQLNetworkStep
  (-exec [_ operation _]
    (.log js/console operation)
    (chan)))
```

Cool, now we've got a valid step. But, it still doesn't do anything with the
operation besides log it. Let's have it post our operation to our GraphQL
server by calling a fictional `make-post` function (assume this returns a
channel).

```clojure
(require '[artemis.network-steps.protocols :refer [GQLNetworkStep]])

(reify
  GQLNetworkStep
  (-exec [_ operation _]
    (.log js/console operation)
    (make-post operation)))
```

Now we've got a fully working network step. But, we're conflating logging with
making requests. Let's tweeze those things apart:

```clojure
(require '[artemis.network-steps.protocols :refer [GQLNetworkStep]])

(reify
  GQLNetworkStep
  (-exec [_ operation _]
    (.log js/console operation)))

(reify
  GQLNetworkStep
  (-exec [_ operation _]
    (make-post operation)))
```

How do we get these two steps to work together? That's where the `exec`
function comes in handy. We can use `exec` to pass an operation to another
step. We'll use a function to take in the next step:

```clojure
(require '[artemis.core :as a]
         '[artemis.network-steps.protocols :refer [GQLNetworkStep]])

(defn log-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (.log js/console operation)
      (a/exec next-step operation context))))

(defn post-step []
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (make-post operation))))

(def network-chain (-> (post-step) log-step))
```

Hopefully you've noticed that our final step, `post-step`, doesn't pass the
operation to the next step. That's because it's our "terminating step", meaning
it's the step responsible for ending the chain by sending the operation to our
server.

You may have also noticed that there's a third argument being passed to each
`exec` called `context`. `context` is an arbitrary map that we can use to pass
information step-to-step. Let's say, for example, that we wanted to include a
header with each of our post requests representing the version of our
application called `X-Client-App-Version`. We can add a step that's responsible
for figuring out the correct version, then use the `context` map to pass it
along to the `post-step`:

```clojure
(require '[artemis.core :as a]
         '[artemis.network-steps.protocols :refer [GQLNetworkStep]])

(defn log-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (.log js/console operation)
      (a/exec next-step operation context))))

(defn version-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (let [v (get-app-version)]
        (a/exec next-step operation (assoc context :app-version v))))))

(defn post-step []
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (make-post operation {:headers {"X-Client-App-Version" (:app-version v)}}))))

(def network-chain (-> (post-step) version-step log-step))
```

Because network steps are just Clojure code, we can do a lot with them,
including conditionally making requests over a particular protocol or to a
particular GraphQL server, mocking a response, and a whole host of other
things.

## The Base HTTP Step

Artemis comes with a simple HTTP network step that you can include when
creating your client. It's the same step that we used back in the first guide,
and it should cover a lot of your needs. Let's take a look at what our initial
client creation code looked like:

```clojure
(require '[artemis.core :as a]
         '[artemis.network-steps.http :as http])

(def net-chain (http/create-network-step "http://localhost:12345/"))
(def client (a/create-client :network-chain net-chain))
```

So, now you can see that our network chain is really just a single step, the
base http step that Artemis comes with. To further elucidate the concept of
building a chain, let's re-implement our logging step, this time using our
base http step as the next step:

```clojure
(require '[artemis.core :as a]
         '[artemis.network-steps.protocols :refer [GQLNetworkStep]]
         '[artemis.network-steps.http :as http])

(defn log-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (.log js/console operation)
      (a/exec next-step operation context))))

(defn http-step [uri]
  (http/create-network-step uri))

(def client (a/create-client :network-chain (-> (http-step "http://localhost:12345/")
                                                log-step)))
```
Great, now we can execute operations on our client and we should first see the
operation logged to our browser's console, and then executed over the network.

```clojure
(require '[artemis.document :refer [parse-document]])

(def planet-info (parse-document "query planetInfo($id:ID!) { planet(id:$id) { id name } }"))

(a/query! client
          planet-info
          {:id "cGxhbmV0czox"}
          :fetch-policy :remote-only)
```

So, the network chain allows us to manage the execution of our GraphQL
operations in a consistent manner. Let's now take a look at the second
configuration parameter when creating our client: the local store.

### Options

The base http step uses [clj-http](https://github.com/r0man/cljs-http), and you
can configure it via the `context` map.

Supported options are:

- `:interchange-format` One of `:json` or `:edn`. Defaults to `:json`
- `:with-credentials?` Whether to send credentials with request
- `:oauth-token` Token for Authorization header value `"Bearer %s"`
- `:basic-auth` Map of `:username` and `:password`
- `:headers` Map of any http headers
