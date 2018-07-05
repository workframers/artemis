(ns artemis.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [artemis.core-test]
            [artemis.core-mutate-test]
            [artemis.core-query-test]
            [artemis.core-subscribe-test]
            [artemis.document-test]
            [artemis.stores.mapgraph-store-test]
            [clojure.spec.alpha :as s]
            [orchestra-cljs.spec.test :as st]))

(st/instrument)
(s/check-asserts true)

(doo-tests 'artemis.core-test
           'artemis.core-mutate-test
           'artemis.core-query-test
           'artemis.core-subscribe-test
           'artemis.document-test
           'artemis.stores.mapgraph-store-test)
