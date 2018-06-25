(ns example.queries
  (:require [artemis.document :refer [parse-document]]))

;; Write a GraphQL query
(def get-repos
  (parse-document
    "query getUserRepos($login: String!) {
       user(login: $login) {
         id
         name
         login
         avatarUrl
         repositories(first: 6, affiliations: OWNER) {
           nodes {
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
         id
         name
         description
         url
         stargazers(first: 30) {
           nodes {
            id
            name
           }
         }
         languages(first: 10) {
           nodes {
             color
             name
           }
         }
       }
     }"))
