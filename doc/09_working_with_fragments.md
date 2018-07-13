# Working With Fragments

Fragments are essential part of writing robust GraphQL queries. Fragments let
you construct sets of fields, and then include them in queries where you need
to.

With Artemis you can easily include a fragment with your query by simplying
adding it into the string to parse:

```clojure
(parse-document
 "query getPerson($id: String!) {
    person(id: $id) {
      id
      ...NameFields
    }
  }

  Fragment NameFields on Person {
    firstName
    lastName
  }")
```

But, what if you wanted to split your fragments out into a different file so
that they can be used in various places in your application? How about
something like this:

```clojure
(ns app.fragments
  (:require [artemis.document :refer [parse-document]]))

(def name-fields
  (parse-document
   "Fragment NameFields on Person {
      firstName
      lastName
    }
  "))
```

When we need to use a fragment within a query, we can just refer to it as
`app.fragments/name-fields` and compose it with our query document using
`artemis.document/compose`:

```clojure
(ns app.core
  (:require [artemis.core :as a]
            [artemis.document :as d :refer [parse-document]]
            [app.fragments :as fragments]))

(def get-person-doc
  (parse-document
   "query getPerson($id: String!) {
      person(id: $id) {
        id
        ...NameFields
      }
    }"))

(a/query! client
          (d/compose get-person-doc fragments/name-fields)
          {:id 1})
```

## Inlining Fragments

Because Artemis produces an AST when you parse a document, it's fairly
straightforward to inline a fragment directly into a query selection set. For
example, if we wanted to inline the `firstName` and `lastName` fields into
our `getPerson` query, we could so by calling
`artemis.document/inline-fragments`:

```clojure
(-> get-person-doc
    (d/compose fragments/name-fields)
    (d/inline-fragments))
```

If we were to execute the above query, it would be like executing something
like:

```graphql
query getPerson($id: String!) {
   person(id: $id) {
     id
     # inlined fields below
     firstName
     lastName
   }
 }
```

## Fragments on Unions

More complex GraphQL schemas are likely to eventually encounter the need for
interfaces and unions. Artemis supports fragments on unions, so the below
would work:

```clojure
(parse-document
  "query search($term: String!) {
    search(term: $term) {
      ... on Person {
        __typename
        id
        firstName
        lastName
      }

      ... on Company {
        __typename
        id
        name
        address
      }
    }
  }")
```

_Keep in mind that because unions switch on type and the Artemis store likely
doesn't know anything about your GraphQL schema types, it's important to
include the `__typename` field in our union selection sets, so that the cache
can correctly match up a selection set with an entity._
