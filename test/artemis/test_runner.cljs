(ns artemis.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [artemis.core-test]
            [artemis.stores.normalized-in-memory-store-test]
            [artemis.stores.mapgraph-store-test]
            [clojure.spec.alpha :as s]
            [orchestra-cljs.spec.test :as st]))

(st/instrument)
(s/check-asserts true)

(doo-tests 'artemis.core-test
           'artemis.stores.normalized-in-memory-store-test
           'artemis.stores.mapgraph-store-test)
