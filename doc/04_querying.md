# Querying

Now that you've learned the basics of Artemis, let's take a look at how to
execute more complex queries.

## Creating a Document

Before moving on, let's take a step-back and look at the `parse-document`
macro. We've been using this macro whenever we've executed a query, so what's
its purpose?

According to the GraphQL spec, a document describes a complete request string
operated on by a GraphQL service. A document looks something like this, then:

```json
query getPerson {
  person(personID: 4) {
    name
  }
}
```

That's great for statically describing the GraphQL query, but strings are
hard to work with in code. So, we use the `parse-document` macro to take those
GraphQL document strings and turn them into something we can use in Clojure.
Whenever we parse a document, we get back an EDN representation of the string
called an AST.

The EDN form of the GraphQL document makes it easier to do programmatic things,
like validating a query, or checking against a local cache (as we'll see later,
Artemis passes the parsed document to the store associated with your client).

_You can do more with the `artemis.document` namespace, but we'll save that for
another section._

## Querying with Variables

Now that we understand how basic queries work, let's take a look at how we can
use variables to create more dynamic and reusable queries. Let's jump back into
our sample application and update our query so that it can be used to get any
user, not just Jerry based on a parameter.

```clojure
(def get-user-doc
  (parse-document
   "query getUser($id: String!) {
      User(id: $id) {
        id
        name
      }
    }"))
```

Great, now let's replace our old query with this new piece of code:

```clojure
(a/query! client
          get-user-doc
          {:id "cjjh9xjbf88990103iyl4zcs8"}
          :fetch-policy :remote-only)
```

You'll notice that we're including a regular Clojure map to specify the
variables to our GraphQL query. You should see "George Costanza" printed to
your screen. Change the `:id` value to `"cjjh9xtgs88b601039hdopcuf"` and
refresh your browser. Now, you should see "Elaine Benes".

Hopefully, it's easy to see how you might be able to construct the variables
map based on runtime behavior, such as query params, user interaction, or some
other input. Ultimately, variables is how you achieve full dynamism with your
GraphQL queries.

## Handling GraphQL Errors

We've already seen a little bit about the messages that Artemis give us
whenever there's an update to our GraphQL queries. We previously used this to
show a loading state, but we can also get error information from our messages
as well.

Let's start by updating our query to look like this:

```clojure
(def get-user-doc
  (parse-document
   "query getUser($id: String!) {
      User(id: $id) {
        id
        name
        dontExist
      }
    }"))
```

Now re-run our query by refreshing the brower. If you log the messages that
come through, you'll notice that the one that corresponds with the server
response includes an `:errors` key.

```clojure
(go-loop []
  (when-let [x (<! get-user-chan)]
    (.log js/console x)
    (reset! app-state {:loading? (:in-flight? x)
                       :jerry    (get-in x [:data :User])})
```

If we get an error are at any point during a query, Artemis will put a message
onto our channel with an `:errors` key, which holds a sequence of error maps,
each describing the error that occurred.

## Fetch Policies

You might have noticed that whenever we've called `query!` we've been passing
a `:fetch-policy` option. Artemis supports keeping a client-side cache of your
GraphQL data.

_The default cache that comes with Artemis is based on
[Mapgraph](https://github.com/stuartsierra/mapgraph), a normalized in-memory
graph store, but you can provide your own, as long as it implements the
`GQLStore` protocol._

When querying, we can specify a fetch policy, which allows you to specify if
the results should be fetched from the server, the cache, or both. The default
policy is `:local-only`, which means we'll only run our query against our
local cache. That's not what we want for our examples, so we've been using
`:remote-only` instead, which will always fetch data from the server. The
policies are explained in more detail below:

#### `:no-cache`
A query will never be read or write to the local store. Instead, it will always
execute a remote query without caching the result. This policy is for when you
want data coming directly from the remote source and don't intend for the
result to ever be used by other queries.

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
the server, but at the cost of an instant response."
