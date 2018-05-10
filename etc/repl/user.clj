(ns user
  (:require [figwheel-sidecar.system :as sys]
            [clojure.pprint :refer [pprint]]
            [com.stuartsierra.component :as component]))

(def system (component/system-map :figwheel-system (sys/figwheel-system (sys/fetch-config))))

(defn go! []
  (alter-var-root #'system component/start)
  (sys/cljs-repl (:figwheel-system system)))
