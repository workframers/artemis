# Querying

As mentioned earlier, the `query!` function can give you more flexibility
when fetching data. Let's go over some of the things you can do with it.

## Creating a Document

Before moving on, let's take a step-back and look at the `parse-document`
macro. We've been using this macro to whenever we've executed a query, so
what's its purpose?

According to the GraphQL spec, a Document describes a complete request string
operated on by a GraphQL service. A Document looks something like this, then:

```json
query getPerson {
  person(personID: 4) {
    name
  }
}
```

That's create for statically describing the GraphQL query, but strings are
hard to work with in code. So, we use the `parse-document` macro to take those
GraphQL document strings and turn them into something we can use in Clojure.
Whenever we parse a document, we get back an instance of the
`artemis.document/Document` record. Given that record, we can now call
`artemis.document/ast` to get an EDN representation of the above string:

```clojure
(require '[artemis.document :refer [ast parse-document]])

(def doc
  (parse-document
   "query getPerson {
      person(personID: 4) {
        name
      }
    }"))

(ast doc)

-> {:operations
    [{:operation-type "query",
      :metadata {:row 0, :column 0, :index 0},
      :operation-name "getPerson",
      :selection-set
      [{:field-name "person",
        :metadata {:row 1, :column 13, :index 31},
        :arguments
        [{:argument-name "personID",
          :metadata {:row 1, :column 20, :index 38},
          :argument-value
          {:value-type :integer,
           :integer 4,
           :metadata {:row 1, :column 30, :index 48}}}],
        :selection-set
        [{:field-name "name",
          :metadata {:row 2, :column 15, :index 68}}]}]}],
    :metadata {:row 0, :column 0, :index 0}}
```

The EDN form of the GraphQL document makes it easier to do programmatic things,
like create validating a query, or checking against a local cache (as we'll
see later, Artemis passes the parsed document to the store associated with your
client).

The `ast` function returns the EDN produced by
[alumbra.parser](https://github.com/alumbra/alumbra.parser), a GraphQL parsing
library.

Once you have a Document instance, it's also useful to be able to get its
original string form, since that's what a GraphQL server expects. The do so,
simply call `artemis.document/source` on the record instance:

```clojure
(require '[artemis.document :refer [source]])

(source doc)
-> "query getPerson {\n\tperson(personID: 4) {\n\t\tname\n\t}\n\t}"
```

## Querying with Variables

Now that we understand how basic queries work, let's take a look at how we can
use variables to create more dynamic and reusable queries. To do so, let's jump
back into our sample application and update our query so that it uses a
variable for the planet query. To get started off, here's what our entire
application code should look like:

```clojure
(ns my-app.view
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.network-steps.http :as http]
            [artemis.document :refer [parse-document]]
            [reagent.core :as r]
            [cljs.core.async :refer [<!]]))

(def gql-result (reagent/atom []))
(def net-chain (http/create-network-step "http://localhost:12345/"))
(def client (a/create-client :network-chain net-chain))

;; This part changed!
(def planet-info (parse-document "query planetInfo($id:ID!) { planet(id:$id) { id name } }"))

(defn my-app []
  (let [planet (some-> @gql-result last :data :planet)]
    [:div
     "The planet is: "
     (:name planet)]))

(let [planet-info-chan (a/query! client
                              planet-info
                              {:id "cGxhbmV0czoz"} ;; Set our variables here!
                              :fetch-policy :remote-only)]
  (go-loop []
    (when-let [r (<! planet-info-chan)]
      (swap! gql-result conj r)
      (recur))))

(defn ^:export main []
  (r/render-component [my-app]
                      (.getElementById js/document "app")))
```

You'll notice that we've changed our `yavin-iv-info` query to the more generic
`planet-info`, which now uses an `id` variable. To fill that variable, we've
simply passed a normal Clojure map specifying the variable values when calling
our `query!` function. That's all there is to it!

Try changing the value of the `id` key in our variables map to
`"cGxhbmV0czox"`. You should now see `"The planet is: Tatooine"` printed to the
page. Hopefully, it's easy to see how you might be able to construct the
variables map based on runtime behavior, such as query params, user
interaction, or some other input. Ultimately, variables is how you achieve full
dynamism with your GraphQL queries.

## Artemis Messages

We've already seen a little bit about the messages that Artemis give us
whenever there's an update to our GraphQL queries. Let's take another look to
see how we can use these messages to handle things like errors and loading.

Let's start by updating our component to look like this:

```clojure
(defn my-app []
  (let [message (last @gql-result)]
    [:div
     (if (and (nil? (:data message)) (:in-flight? message))
       "Loading..."
       [:div
        "The planet is: "
        (-> message :data :planet :name)])]))
```

If you're on a fast network, you likely won't see a difference. However, you
can throttle your requests to see the change. If you're using Chrome or
Firefox, you can easily throttle requests with the developer tools -- a 3G
connection should be enough to notice the different rendering cycles as the
Artemis client puts messages onto our channel to give us a status update on our
query. We use the information available on each of these messages to
conditionally render the loading text or the planet name.

Errors can be handled in much the same way. If we get an error are at any point
in time, Artemis will put a message onto our channel with an `:errors` key,
which holds a sequence of error maps, each describing the error that occurred.
Try changing the planet `id` variable to `"not-a-planet"` and updating our
component to this:

```clojure
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
```

You should now see the errors on your page.

Hopefully, it's starting to become clear how you can use leverage the messages
Artemis puts on the channel to predictably render UI based on the state of our
GraphQL queries.

Let's now move onto mutations, the other side of the GraphQL coin.

### Complete Example
```clojure
(ns my-app.view
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.network-steps.http :as http]
            [artemis.document :refer [parse-document]]
            [reagent.core :as r]
            [cljs.core.async :refer [<!]]))

(def gql-result (reagent/atom []))
(def net-chain (http/create-network-step "http://localhost:12345/"))
(def client (a/create-client :network-chain net-chain))
(def planet-info (parse-document "query planetInfo($id:ID!) { planet(id:$id) { id name } }"))

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
                                 :fetch-policy :remote-only)]
  (go-loop []
    (when-let [r (<! planet-info-chan)]
      (swap! gql-result conj r)
      (recur))))

(defn ^:export main []
  (r/render-component [my-app]
                      (.getElementById js/document "app")))
```
