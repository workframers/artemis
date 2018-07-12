# Interacting With A View

Artemis should work just fine with any view library. We'll first use plain-ol'
ClojureScript to render to the DOM, then we'll show an example of how you
might integrate with a reactive view library like Reagent.

Let's start with some familiar code, then add in some view functions that
use the `goog.dom` namespace.

```clojure
(ns app.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [artemis.core :as a]
            [artemis.document :refer [parse-document]]
            [artemis.network-steps.http :as http]
            [artemis.stores.mapgraph.core :as mgs]
            [goog.dom :as goog-dom]))

;;; Set-up

(def graphcool-url "https://api.graph.cool/simple/v1/cjjh9nmy118fs0127i5t71oxe")

(def network-chain (http/create-network-step graphcool-url))

(def store (mgs/create-store))

(def client (a/create-client :network-chain network-chain
                             :store         store))

;;; View

(defn show-loading! []
  (let [loading (goog-dom/createDom "em" nil "Loading...")
        app     (goog-dom/getElement "app")]
    (goog-dom/removeChildren app)
    (goog-dom/appendChild app loading)))

(defn show-jerry! [jerry]
  (let [jerry-name (goog-dom/createDom "h1" nil (:name jerry))
        app        (goog-dom/getElement "app")]
    (goog-dom/removeChildren app)
    (goog-dom/appendChild app jerry-name)))

;;; Query

(def get-jerry-doc
  (parse-document
   "query getJerry {
      User(id: \"cjjh9x0q97x7r0111osr3t352\") {
        id
        name
      }
    }"))

(defn get-jerry []
  (let [get-jerry-chan (a/query! client get-jerry-doc :fetch-policy :remote-only)]
    (go-loop []
      (when-let [x (<! get-jerry-chan)]
        (if (:in-flight? x)
          (show-loading!)
          (show-jerry! (:User (:data x))))
        (recur)))))

;;; Main

(defn main ^:export []
  (get-jerry))
```

If you open up your browser, you should see some text rendered. Hopefully this
barebones example shows you how you can integrate Artemis with any approach to
rendering views. A more realisic, or at least common, example might include
React. Let's try the same example, but replace `goog.dom` with
[Reagent](http://reagent-project.github.io/), a ClojureScript interface for
React.

First, install and require the latest version of Reagen. Then, let's start by
replacing our view and query code with the following:

```clojure
;;; View

(defonce app-state (r/atom {}))

(defn loading []
  [:em "Loading..."])

(defn jerry [jerry]
  [:h1 (:name jerry)])

(defn app []
  (if (:loading? @app-state)
    [loading]
    [jerry (:jerry @app-state)]))

;;; Query

(def get-jerry-doc
  (parse-document
   "query getJerry {
      User(id: \"cjjh9x0q97x7r0111osr3t352\") {
        id
        name
      }
    }"))

(defn get-jerry []
  (let [get-jerry-chan (a/query! client get-jerry-doc :fetch-policy :remote-only)]
    (go-loop []
      (when-let [x (<! get-jerry-chan)]
        (reset! app-state {:loading? (:in-flight? x)
                           :jerry    (get-in x [:data :User])})
        (recur)))))
```

Finally, add a section that mounts our React application:

```clojure
;;; Main

(defn mount []
  (r/render [app] (.getElementById js/document "app")))

(defn main ^:export []
  (get-jerry)
  (mount))
```

Refresh your browser and you should get the same results, but this time going
through Reagent. Of course, this is a trivial example, but go ahead and try
expanding on this code. You might even consider swapping Reagent for
[Rum](https://github.com/tonsky/rum), or sticking with Reagent, but adding-in
[re-frame](https://github.com/Day8/re-frame). Either case should be fairly
easy because within or `go-loop` we can manage our app-state however we'd
like.
