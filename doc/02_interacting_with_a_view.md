# Interacting With A View

To interact with a Reagent view, let's bring over the code we just wrote and
then create a component.

```clojure
(ns my-app.view
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.network-steps.http :as http]
            [artemis.document :refer [parse-document]]
            [reagent.core :as r]
            [cljs.core.async :refer [<!]]))

(def net-chain (http/create-network-step "http://localhost:12345/"))
(def client (a/create-client :network-chain net-chain))
(def yavin-iv-info (parse-document "{ planet(id: \"cGxhbmV0czoz\") { id name } }"))

(a/query! client
          yavin-iv-info
          :fetch-policy :remote-only)

(defn my-app []
  [:div "Hello SWAPI!"])

(defn ^:export main []
  (r/render-component [my-app]
                      (.getElementById js/document "app")))
```

Now, open up your browser and you should see the Reagent component rendered and
the network traffic should still show our GraphQL query.

You may have noticed that we've been passing a `:fetch-policy` option when
calling `query!`. All calls to `query!` return a core.async channel, and
depending on the `:fetch-policy` we set, Artemis will put messages onto the
channel at certain times. We'll go over all the fetch policies in a bit, but
for now let's continue using the `:remote-only` policy to query our server and
render some data to the page.

Let's create a ratom to hold our query result. At the top of our code let's add:

```clojure
(def gql-result (r/atom nil))
```

Then, change your component to look like this:

```clojure
(defn my-app []
  [:pre
    (with-our-str (print @gql-result))])
```

Now we'll be able to `reset!` our ratom when we get a message from Artemis. Do
so by updating our `query!` so that it's within a go block, allowing us to take
from the channel.

```clojure
(go (let [x (<! (a/query! client
                          yavin-iv-info
                          :fetch-policy :remote-only))]
      (reset! gql-result x)))
```

Your page should now display something like this:

```clojure
{:data nil, :variables nil, :in-flight? true, :network-status :fetching}
```

You'll notice that data is always `nil`. That can't be right -- recall we saw
that data come through on our network tab. The reason the data appears as `nil`
on our view is because the `:remote-only` policy will put two messages onto the
channel returned by `query!`. In order to see both messages, let's update our
ratom to a vector:

```clojure
(def gql-result (reagent/atom []))
```

And, let's use `go-loop` instead:

```clojure
(let [yavin-iv-chan (a/query! client
                              yavin-iv-info
                              :fetch-policy :remote-only)]
  (go-loop []
    (when-let [r (<! yavin-iv-chan)]
      (swap! gql-result conj r)
      (recur))))
```

Our view now shows this:

```clojure
[{:data nil, :variables nil, :in-flight? true, :network-status :fetching},
 {:data {:planet {:id cGxhbmV0czoz, :name Yavin IV}}, :variables nil, :in-flight? false, :network-status :ready}]
```

Awesome! And, if we inspect each of the messages, it should be apparent what
each tells us about our query.

The first message let's us know that we don't have any data available, and
that's because our network status is currently `:fetching`, meaning we're
getting the data. You'll also notice that `:in-flight?` is `true` for the first
message, so we know our query is currently over the wire. The second message,
of course, shows different values for these keys.

We can use this kind of information per-message to easily render loading
indicators, or delay transitions. And with that, we arrive at the main design
pattern that Artemis presents: using a channel to hold stateful, ordered
updates on our data fetching and manipulation. This becomes especially useful
when we're querying multiple sources, such as a local cache and a server, as
we'll see later.

For now, let's just use the data to render our planet's name. Update our
component to get the data:

```clojure
(defn my-app []
  (let [planet (some-> @gql-result last :data :planet)]
    [:div
     "The planet is: "
     (:name planet)]))
```
You should see the text "The planet is: Yavin IV" on your page. In the next
guide, we'll learn how to do more with the `query!` function.

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
(def yavin-iv-info (parse-document "{ planet(id: \"cGxhbmV0czoz\") { id name } }"))

(defn my-app []
  (let [planet (some-> @gql-result last :data :planet)]
    [:div
     "The planet is: "
     (:name planet)]))

(let [yavin-iv-chan (a/query! client
                              yavin-iv-info
                              :fetch-policy :remote-only)]
  (go-loop []
    (when-let [r (<! yavin-iv-chan)]
      (swap! gql-result conj r)
      (recur))))

(defn ^:export main []
  (r/render-component [my-app]
                      (.getElementById js/document "app")))
```
