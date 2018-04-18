# Mutating

Now that we've learned how to query for data with Artemis, let's take a look
at updating data. Unfortunately, the SWAPI project doesn't come with any
pre-defined mutations, so you won't be able to see the changes reflected in
your sample application, but to make the ideas clear, we'll still write out the
code as if it did.

_If you're comfortable writing GraphQL mutations on the server, feel free to
implement the below mutation, which will allow you to go through the full
experience._

## Writing a Mutation

Let's start off by creating a mutation document in our project:

```clojure
(def add-planet (parse-document "mutation addPlanet($name:String!) { addPlanet(name:$name) { id name } }"))
```

Now, just like with `query!`, we can pass our document and variables to the
`artemis.core/mutate!` function. And, like it's counterpart function, `mutate!`
also returns a channel, so we'll want to take messages off it:

```clojure
(let [add-planet-chan (a/mutate! client
                                 add-planet
                                 {:name "Scarif"})]
  (go-loop []
    (when-let [add-planet-chan (<! add-planet-chan)]
      (swap! gql-result conj x)
      (recur))))
```

Because, mutations also include the desired return data, the messages that
Artemis puts on your channel are pretty much the same as those you'll get when
a running a query -- you'll have access to the returned `:data`, any `:errors`,
as well as meta-data, like `:in-flight?` and `:network-status`.

That's about all it takes to execute GraphQL mutations with Artemis.

Let's now look at how you can tell Artemis to do certain things unique to your
application when executing queries and mutations.
