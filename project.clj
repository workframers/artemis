(defproject com.workframe/artemis "0.3.0-SNAPSHOT"
  :description "A minimal ClojureScript GraphQL client"
  :url         "https://github.com/workframers/artemis"
  :license     {:name "Apache License, Version 2.0"
                :url  "http://www.apache.org/licenses/LICENSE-2.0"}

  :dependencies
  [[org.clojure/clojure             "1.9.0"]
   [org.clojure/clojurescript       "1.9.946"]
   [org.clojure/core.async          "0.4.474"]
   [cljs-http                       "0.1.44"]
   [haslett                         "0.1.2"]
   [shodan                          "0.4.2"]
   [diffit                          "1.0.0"]
   [floatingpointio/graphql-builder "0.1.6"]
   [com.rpl/specter                 "1.1.2"]]

  :exclusions
  [org.clojure/core.async]

  :plugins
  [[lein-cljsbuild "1.1.7"]]

  :aliases
  {"test"      ["with-profile" "test" "doo" "chromium-no-sandbox" "test" "once"]
   "test-auto" ["with-profile" "test" "doo" "chrome" "test" "auto"]
   "example"   ["with-profile" "+examples" "run" "-m" "clojure.main" "script/repl.clj" "--example"]}

  :clean-targets
  ^{:protect false} [:target-path "resources/js"]

  :codox
  {:metadata   {:doc/format :markdown}
   :language   :clojurescript
   :themes     [:rdash]
   :namespaces [#"^artemis\.((network-steps|stores)\.)?(\w+|)$"
                artemis.stores.mapgraph.core]
   :source-uri "https://github.com/workframers/artemis/blob/artemis-{version}/{filepath}#L{line}"}

  :profiles
  {:dev {:dependencies
         [[figwheel-sidecar   "0.5.0"]
          [binaryage/devtools "0.9.9"]]}

   :docs {:plugins
          [[lein-codox       "0.10.7"]
           [lein-asciidoctor "0.1.17" :exclusions [org.slf4j/slf4j-api]]]

          :dependencies
          [[codox-theme-rdash "0.1.2"]]}

   :examples {:source-paths
              ["examples/common"]

              :dependencies
              [[re-frame "0.10.5"]]}

   :test {:plugins
          [[lein-doo "0.1.10"]]

          :dependencies
          [[org.clojure/test.check "0.9.0"]
           [orchestra              "2017.11.12-1"]]

          :doo
          {:paths {:karma "node_modules/.bin/karma"}
           :karma {:launchers {:chromium-no-sandbox
                               {:plugin "karma-chrome-launcher"
                                :name   "Chromium_no_sandbox"}}
                   :config    {"customLaunchers"
                               {"Chromium_no_sandbox"
                                {"base"  "ChromiumHeadless"
                                 "flags" ["--no-sandbox"
                                          "--headless"
                                          "--disable-gpu"
                                          "--disable-translate"
                                          "--disable-extensions"]}}}}}

          :cljsbuild
          {:builds
           {:test
            {:source-paths
             ["src" "test"]

             :compiler
             {:output-to       "target/main.js"
              :output-dir      "target"
              :main            artemis.test-runner
              :optimizations   :none
              :preloads        []}}}}}})
