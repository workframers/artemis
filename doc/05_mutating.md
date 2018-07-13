# Mutating

## Mutating with Variables

Like with queries, we can pass a map of variables when calling `mutate!`:

```clojure
(def create-user
  (parse-document
   "mutation createUser($name: String!) {
      createUser(name: $name) {
        id
        name
      }
    }"))

(a/mutate! client create-user {:name "Art Vandelay"})
```

## Updating the Cache

If you're using the default Mapgraph store, most of the time your client-side
store will automatically update as you run queries and mutations. On some
ocassions, however, you might want to update the cache after a mutation is
written to the store; for example, deleting and adding items to a list. Artemis
provides an `:after-write` hook that you can use for these scenarios. The
function provided to `:after-write` will be called with a single map of
information (shape below) and is required to return an updated store:

```clojure
{:store       <the client's store>
 :result      <the result of a mutation>
 :document    <the mutation document>
 :variables   <the map of arguments>
 :optimistic? <a boolean specifying if the write to the store was optimistic> }
```

Let's use adding an item to a list as an example of how to use the
`:after-write` hook. We'll continue with Reagent for our example. Let's start
with some scaffolding:

```clojure
(ns app.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.document :refer [parse-document]]
            [artemis.network-steps.http :as http]
            [artemis.stores.mapgraph.core :as mgs]
            [reagent.core :as r]))

;;; Set-up

(def graphcool-url "https://api.graph.cool/simple/v1/cjjh9nmy118fs0127i5t71oxe")

(def network-chain (http/create-network-step graphcool-url))

(def store (mgs/create-store))

(def client (a/create-client :network-chain network-chain
                             :store         store))

;;; GraphQL

(def get-users-doc
  (parse-document
    "query getUsers {
       allUsers {
         id
         name
       }
     }"))

(def create-user-doc
  (parse-document
    "mutation createUser($name: String!) {
      createUser(name: $name) {
        id
        name
      }
    }"))

```

The above is code we've seen before. Let's now write functions that will allow
us to get all of our users and update the list of users to include a new user.
We'll be using the `read` and `write` functions to do so:

```clojure
(defn all-users [store]
  (-> store
      (a/read get-users-doc {})
      (get-in [:data :allUsers])
      not-empty))

(defn add-user [store new-user]
  (let [users (all-users store)]
    (a/write store
             {:data {:allUsers (conj users new-user)}}
             get-users-doc
             {})))
```

_Reading and writing to the cache our coverd in more detail in the Direct
Cache Access topic._

Above, you'll notice that we call read and write with the `get-users-doc`,
that's because we want to show the list of users queried by `get-users-doc` and
update that same list whenever we add a new user by conjoining that new user
onto the list and writing it as the updated list.

_Not every mutation requires manually updating. If you're updating a single
entity, you usually don’t need to do any manual work. The reasons why are
explained in more-depth in the section on the local store._

Now, let's create a function to update our app-state on GraphQL queries and
mutations.

```clojure
(defonce app-state (r/atom {:store (a/store client)}))

(defn execute! [c]
  (go-loop []
    (when-let [x (<! c)]
      (swap! app-state assoc :store (a/store client))
      (recur))))
```

And, finally, our Reagent views:

```clojure
(defn create-user-input-and-button []
  (let [text (r/atom "")]
    (fn []
      [:form {:on-submit (fn [event]
                            (.preventDefault event)
                            (execute!
                             (a/mutate! client
                                        create-user-doc
                                        {:name @text}
                                        :after-write (fn [{:keys [store result]}]
                                                       (add-user store (get-in result [:data :createUser])))))
                            (reset! text ""))}
       [:input {:type      :text
                :value     @text
                :on-change (fn [event]
                             (reset! text (.. event -target -value)))}]
       [:input {:type      :submit
                :value     "Create"}]])))

(defn app []
  (when-let [users (all-users (:store @app-state))]
    [:div
     [:ul
      (for [user users]
        [:li {:key (:id user)}
         (:name user)])]
     [create-user-input-and-button]]))

;;; Main

(defn mount []
  (r/render [app] (.getElementById js/document "app")))

(defn main ^:export []
  (mount))
```

If you put the code snippets above all together, then run your code, you should
see a list of users and a form that allows you to create a new user. Give it a
shot; you should see the newly created user added to the bottom of the list.

## Optimistic Updates

The above example is great, but there's a little delay between submitting our
form and the new user popping into place. Let's improve the experience a bit
by performing the update optimistically. Optimistically updating refers to a
UX pattern whereby you simulate the results of a mutation and update the UI
before receiving a response from the server in order to create an application
that feels snappier and provides more immediate feedback to the user.

In order to optimistically update our application on a mutation, we specify an
`:optimistic-result` when calling `mutate!`. Here's our updated submit
function:

```clojure
(fn [event]
  (let [temp-id   "temp-id"
        user-name @text]
    (.preventDefault event)
    (execute!
     (a/mutate! client
                create-user-doc
                {:name user-name}
                :optimistic-result {:data {:createUser {:id   temp-id
                                                        :name user-name}}}
                :after-write (fn [{:keys [store result optimistic?] :as args}]
                               (let [new-user (get-in result [:data :createUser])]
                                 (if optimistic?
                                   (add-user store new-user)
                                   (replace-user store temp-id new-user))))))
    (reset! text "")))
```

You can see we've provided a simulated result for our `createUser` mutation.
Our `:after-write` function gets called on both the optimistic write, and the
write that occurs after our server response, so we can use the `:optimistic?`
flag to, first, add our simulated user (denoted by the temp-id), then replace
the optimistic user with the confirmed version.

For reference, here's what `replace-user` might look like:

```clojure
(defn replace-user [store user-id new-user]
  (let [users (map (fn [user] (if (= (:id user) user-id) new-user user))
                   (all-users store))]
    (a/write store
             {:data {:allUsers users}}
             get-users-doc
             {})))
```
