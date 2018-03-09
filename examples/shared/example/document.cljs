(ns example.queries
  (:require [artemis.document :refer [parse-document]]))

;; Write a GraphQL query
(def get-repo
  (parse-document
   "query {
      repository(owner: \"octocat\", name: \"Hello-World\") {
        id
        name
        description
        createdAt
        url
        sshUrl
        pushedAt
        labels(first:5) {
          nodes {
            id
            name
            repository {
              id
              name
            }
          }
        }
        stargazers(first:5) {
          nodes {
            id
            name
            email
              repositories(first:2) {
                nodes {
                  id
                  name
                }
              }
          }
        }
      }
    }"))
