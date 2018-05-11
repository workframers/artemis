# Advanced

In this guide we'll cover a few advanced topics and show some example code.

## Writing Your Queries in EDN with Venia

If you're not interested in writing string-style GraphQL queries you may want
to give [Venia](https://github.com/Vincit/venia) a try. Venia allows you to
generate valid GraphQL queries with Clojure data structures.

We can use Venia with Artemis by writing a little macro. Let's call it `vdoc`:

```clojure
(require '[venia.core :as v])

(defmacro vdoc [source]
  (let [s (v/graphql-query source)]
    `(artemis.document/parse-document ~s)))
```

_Keep in mind that because the `vdoc` macro emits a call to `parse-document`,
you'll still have to require the `artemis.document` namespace when using it._

Now that we have `vdoc`, we can do this:

```clojure
;; Old string-based query
;; (def planet-info
;;   (parse-document
;;    "query planetInfo($id:ID!) {
;;      planet(id:$id) {
;;        __typename
;;        id
;;        name
;;      }
;;    }"))

;; New venia query
(def planet-info
 (vdoc {:venia/operation {:operation/type :query
                          :operation/name "planetInfo"}
        :venia/variables [{:variable/name "id"
                           :variable/type :ID!}]
        :venia/queries   [{:query/data [:planet
                                        {:id :$id}
                                        [:meta/typename :id :name]]}]}))
```
You can use a similar approach if you'd like to use `.graphql` files, whether
stand-alone or through something like
[graphql-builder](https://github.com/retro/graphql-builder).

## Optimistic Mutations

With Artemis we can perform optimistic mutations by simulating the server's
response and passing it as the value to the `:optimistic-result` option of the
`mutate!` function. When the Artemis client sees this, it will try to
immediately write the result to the local store and then return the result
of the mutation's query against the store. Taking our example from the Mutating
section, we would do something like:

```clojure
(def add-planet
  (parse-document
   "mutation addPlanet($name:String!) {
      addPlanet(name:$name) {
        __typename
        id
        name
      }
    }"))

(let [add-planet-chan (a/mutate! client
                                 add-planet
                                 {:name "Scarif"}
                                 ;; Optimistic result here!
                                 :optimistic-result {:addPlanet
                                                     {:__typename "Planet"
                                                      :id         (mock-id)
                                                      :name       "Scarif"}})]
  (go-loop []
    (when-let [add-planet-chan (<! add-planet-chan)]
      (swap! gql-result conj x)
      (recur))))
```

Notice that we're attempting to predict the exact response we expect back from
our server, including the `__typename` value. That's because that exact value
we'll be handed off to our store for writing.

## Artemis and re-frame

Reactive programming is popular pattern in ClojureScript. Artemis itself is
fairly simple, however, and doesn't include any reactive functionality. In our
examples, we've been leveraging Reagent ratoms to give us reactivity. You can
continue with this approach, or swap it our for some other library that gives
you reactive functionality, or even abandon reactivity all together and update
the UI however you'd like. Not forcing you into a particular paradigm was one
of the goals we first had when creating Artemis.

Nevertheless, reactivity is, again, quite popular and with good reasons.
[re-frame](https://github.com/Day8/re-frame) is a popular library for
reactively managing state in a Reagent applications. We can leverage a lot of
what it offers and tie it into Artemis for a fully reactive GraphQL experience.

A complete re-frame example is including in the Artemis repo. Go ahead and
clone it, then run `lein example re-frame` to start the application. The source
code is located in the `examples/re_frame/example/` directory.

At a high-level, we use `reg-sub-raw` to create a subscription off of the
Artemis channel so that any new message triggers an update. We then subscribe
in our view by specifying the GraphQL query.

Our planet component would look something like this:

```clojure
(def planet-info
  (parse-document
   "query planetInfo($id:ID!) {
      planet(id:$id) {
        id
        name
      }
    }"))

(defn my-app []
  (let [message @(subscribe [:artemis/query
                             planet-info
                             {:id "cGxhbmV0czox"}
                             {:fetch-policy :remote-only}])]
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
```

## Polling

Because Artemis automatically closes our channels at the appropriate time,
it's easy to create a little utility for creating a poll:

```clojure
(defn poll! [query-fn ms]
  (query-fn :remote-only)
  (js/setInterval (fn [] (query-fn :local-then-remote)) ms))
```

Then wrap your query in a function that takes a fetch policy and pass it to
`poll!` along with a `ms` value:

```clojure
(def planet-info
  (parse-document
   "query planetInfo($id:ID!) {
      planet(id:$id) {
        id
        name
      }
    }"))

(defn q! [fp]
  (let [c (a/query! client
                    planet-info
                    {:id "cGxhbmV0czox"}
                    :fetch-policy fp)]
    (go-loop []
      (when-let [x (<! c)]
        (.log js/console x)
        (swap! gql-result conj x)
        (recur)))))

(poll! q! 5000)
```

## GraphQL Subscriptions

Most of the guides cover querying and mutating. The GraphQL spec, however, also
supports a third operation type: subscriptions. To create subscription with
Artemis, call `artemis.core/subscribe!`, passing it a client, document,
variables, and options. Artemis will attempt to set-up a subscription with your
GraphQL server via WebSockets, so it's essential that your network chain
includes a step that establishes the WebSocket connection. For convenience,
Artemis includes a basic network step that'll manage WebSocket-based GraphQL
subscriptions:

```clojure
(require '[artemis.network-steps.ws-subscription :as ws])

(ws/create-ws-subscription-step "ws://localhost:4000/subscriptions")
```

To combine it with an exisiting step that executes your queries and mutations
(the basic http step, for example), you can use the `with-ws-subscriptions`
helper function.

```clojure
(require '[artemis.network-steps.http :as http]
         '[artemis.network-steps.ws-subscription :as ws])

(def net-chain (ws/with-ws-subscriptions
                (http/create-network-step "http://localhost:4000/graphql")
                (ws/create-ws-subscription-step "ws://localhost:4000/subscriptions"))
```

With that all done, you can subscribe:

```clojure
(let [message-added-doc  (parse-document
                          "subscription($id:ID!) {
                            messageAdded(channelId:$id) {
                              id
                              text
                            }
                          }")
      message-added-chan (a/subscribe! client message-added-doc {:id 1})]
  (go-loop []
    (when-let [r (<! message-added-chan)]
      (.log js/console "Message added:" r)
      (recur))))
```
