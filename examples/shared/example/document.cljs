(ns example.queries
  (:require [artemis.document :refer [parse-document]]))

;; Write a GraphQL query
(def get-repos
  (parse-document
    "query getUserRepos($login: String!) {
       user(login: $login) {
         __typename
         id
         name
         login
         repositories(first: 5) {
           __typename
           nodes {
             __typename
             id
             name
           }
         }
       }
     }"))

;; Write another GraphQL query
(def get-repo
  (parse-document
    "query getRepo($owner: String!, $name: String!) {
       repository(owner: $owner, name: $name) {
         __typename
         id
         name
         description
         url
         languages(first: 10) {
           nodes {
             color
             name
           }
         }
       }
     }"))
