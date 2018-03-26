(require '[figwheel-sidecar.repl-api :as fig])

(defn- read-edn-file [file-name]
  (let [file (clojure.java.io/file file-name)]
    (when-let [body (slurp file)]
      (read-string body))))

(defn- build-config [fig-edn example]
  {:figwheel-options (dissoc fig-edn :builds)
   :all-builds [(if example
                  (-> (merge-with into
                       (get-in fig-edn [:builds :dev])
                       (get-in fig-edn [:builds :example])
                       (get-in fig-edn [:builds example]))
                      (assoc :id example))
                  (-> (get-in fig-edn [:builds :dev])
                      (assoc :id :dev)))]
   :build-ids [(or example :dev)]})

;; Start figwheel
(let [fig-edn (read-edn-file "figwheel.edn")
      example (when (= (first *command-line-args*) "--example")
                (keyword (second *command-line-args*)))]
  (fig/start-figwheel! (build-config fig-edn example))
  (fig/cljs-repl))
