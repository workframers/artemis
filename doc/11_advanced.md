# Advanced

In this guide we'll cover a few advanced topics and show some example code.

## Writing Your Queries in .graphql Files

If you don't like writing your GraphQL queries as part of your ClojureScript
code and instead prefer to write them in `.graphql` or `.gql` files, you can
replace your usage of `parse-document` with `parse-document-files`, which
will `slurp` your file, so you can do all of the following:

```clojure
(require '[artemis.document :refer [parse-document-files]])

(parse-document-files "query-a.gql") ; parsing a resource
(parse-document-files "http://my-app.com/query-a.gql") ; parsing a remote file
(parse-document-files (java.io.FileReader. "/Lib/Queries/query-a.gql")) ; parsing a file on the file system
```

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
;;    "query getBook($id: String!) {
;;      book(id: $id) {
;;        id
;;        title
;;      }
;;    }"))

;; New venia query
(def planet-info
  (vdoc {:venia/operation {:operation/type :query
                           :operation/name "getBook"}
         :venia/variables [{:variable/name "id"
                            :variable/type :String!}]
         :venia/queries   [{:query/data [:book
                                         {:id :$id}
                                         [:id :title]]}]}))
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
(def planet-doc
  (parse-document
   "query book($id:ID!) {
      book(id:$id) {
        title
        abstract
      }
    }"))

(defn q! [fp]
  (let [c (a/query! client
                    book-doc
                    {:id "cGxhbmV0czox"}
                    :fetch-policy fp)]
    (go-loop []
      (when-let [x (<! c)]
        (.log js/console x)
        (recur)))))

(poll! q! 5000)
```

## GraphQL Subscriptions _(experimental)_

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

## Colocating and Composing Queries

Because we've been using the `parse-document` macro, it's fairly easy to
colocate your GraphQL queries and fragments with your views. `parse-document`,
however is a macro, and it expects a string as it's only argument. If you
try to do something like `(parse-document my-string)` you'll get a compiler
error. There are a few reasons for this.

First, the GraphQL spec _*strongly*_ encourages static GraphQL queries. By
accepting only raw strings, the `parse-document` macro ensures that your
creating your queries statically.

Second, creating the document AST is a fairly expensive process, so by doing
it at compile time we can avoid the runtime overhead.

The macro solution has its benefits, then, but it does pose a problem when
you want to dynamically compose multiple queries and fragments together.

To solve this problem, Artemis comes with a `artemis.document/compose`
function, which takes two or more parsed documents and composes them together
to create a single document:

```clojure
(require '[artemis.core :as a]
         '[artemis.document :as d :refer [parse-document]])

(def get-person-query
  (parse-document
   "query getPerson($id: String!) {
     person($id: id) {
       id
       ...PersonFields
     }
   }"))

(def person-fields-fragment
  (parse-document
   "fragment PersonFields on Person {
     firstName
     lastName
     avatar {
       ...ImageFields
     }
   }"))

(def image-fields-fragment
  (parse-document
   "fragment ImageFields on Image {
      url
      size
   }"))

(def composed-doc
  (d/compose get-person-query
             person-fields-fragment
             image-fields-fragment))

(a/query! composed-doc {:id 1})
```

## Selecting a Subset of Data

In some cases you might be dealing with a large tree of data, but only
need to use a subset of that data. In that case, you can use
`artemis.document/select` to chose what you want. For example, let's you've
have the following data:

```clojure
{:book
 {:id 1,
  :title "Book A",
  :abstract "Lorem ipsum dolor...",
  :author {:id 2, :firstName "Alice", :lastName "Jones"}}}
```

And, you don't care about anything but the author's first and last names. You
can use `select` to grab exactly what you want:

```clojure
(require '[artemis.document :as d :refer [parse-document]])

(def doc
  (parse-document
   "{
      book {
        author {
          firstName
          lastName
        }
      }
    }"))

(d/select doc book-data)
;; => {:book {:author {:firstName "Alice", :lastName "Jones"}}}
```

The previous example used a query to select, but you can also use a fragment:

```clojure
(def doc
  (parse-document
   "fragment AuthorNames on Book {
      book {
        author {
          firstName
          lastName
        }
      }
    }"))

(d/select doc book-data)
```

## Setting an Auth Header

The default HTTP network step that Artemis comes with support for adding
auth headers or cookies. You can do so by simply setting them in context
to the network chain. Here are a few examples:

```clojure
(require '[artemis.core :as a]
         '[artemis.network-steps.protocols :refer [GQLNetworkStep]])

(defn basic-auth-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (a/exec next-step
              operation
              (assoc context :basic-auth {:username "bob"
                                          :password "secret"})))))

(defn oauth-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (a/exec next-step
              operation
              (assoc context :basic-auth {:with-credentials? false
                                          :oauth-token       "SecretBearerToken"})))))

(defn auth-headers-step [next-step]
  (reify
    GQLNetworkStep
    (-exec [_ operation context]
      (a/exec next-step
              operation
              (assoc context :basic-auth {:with-credentials? false
                                          :headers           {"Authorization" "SecretToken"}})))))
```

You could then use any of the above when setting up your network chain, lets
use the last one as an example:

```clojure
(def client (a/create-client :network-chain (-> (http/create-network-step "/my-graphql-api")
                                                auth-headers-step)))
```
