# Intro
Artemis is a small library that helps you build GraphQL clients in
ClojureScript. It's heavily inspired and borrows many ideas from
[apollo-client](https://github.com/apollographql/apollo-client/tree/master/packages/apollo-client),
but it's written entirely in ClojureScript and specifically designed to be used
by ClojureSript applications.

Artemis relies on `core.async` to provide a model around the asynchronous
nature of querying a GraphQL application and provides a set of protocols for
extensibility.

The main objective of this library is to provide a simple way to execute
GraphQL queries and mutations in pure ClojureScript. As such, it does not
include any built-in integrations with popular view frameworks, such as
Reagent or Rum. Instead, Artemis provides a consistent, predictable approach
to accessing and updating GraphQL data that you can easily incorporate into
your codebase, whatever that might look like.

## Why Artemis
There are several JavaScript-based GraphQL libraries and using them from
ClojureScript isn't terribly difficult. However, we found a few problems with
that approach:

First, it required writing a lot of interop code, which isn't fun.

Second, GraphQL is a spec for accessing data. Accordingly, using a JavaScript
library in Clojurescript meant constantly translating between Clojure data
structures and JavaScript data structures, a costly endeavor.

We felt there was an opening for a ClojureScript library that allowed you
to access GraphQL data without having to constantly dip in and out of
JavaScript.

If you're looking for a library writen entirely in ClojureScript that provides
a consistent and simple approach to fetching and updating remote GraphQL data,
while also caching and normalizing that data at the client-level, then Artemis
might work for you.

Continue reading to see how it all works.
