(defproject com.workframe/artemis "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.946"]
                 [cljs-http "0.1.44"]
                 [alumbra/parser "0.1.7"]
                 [alumbra/errors "0.1.1"]]
  :plugins [[lein-doo "0.1.8"]
            [lein-cljsbuild "1.1.7"]]
  :aliases {"test" ["test-jvm"]
            "test-chrome" ["with-profile" "test" "doo" "chrome" "test" "once"]
            "test-jvm" ["with-profile" "test" "doo" "rhino" "test" "once"]
            "example" ["with-profile" "+examples" "run" "-m" "clojure.main" "script/repl.clj" "--example"]}
  :clean-targets ^{:protect false} [:target-path "resources/js"]
  :codox {:metadata   {:doc/format :markdown}
          :language   :clojurescript
          :themes     [:rdash]
          :source-uri "https://github.com/workframers/artemis/blob/artemis-{version}/{filepath}#L{line}"}
  :profiles
  {:dev {:dependencies [[figwheel-sidecar "0.5.0"]
                        [binaryage/devtools "0.9.9"]]}
   :docs {:plugins [[lein-codox "0.10.3"]
                    [lein-asciidoctor "0.1.15" :exclusions [org.slf4j/slf4j-api]]]
          :dependencies [[codox-theme-rdash "0.1.2"]]}
   :examples {:source-paths ["examples/common"]
              :dependencies [[re-frame "0.10.5"]]}
   :test {:dependencies [[org.mozilla/rhino "1.7.7"]
                         [org.clojure/test.check "0.9.0"]
                         [orchestra "2017.11.12-1"]]
          :doo {:paths {:karma "node_modules/.bin/karma"
                        :rhino "lein run -m org.mozilla.javascript.tools.shell.Main"}}
          :cljsbuild
          {:builds
           {:test
            {:source-paths ["src" "test"]
             :compiler {:output-to "target/main.js"
                        :output-dir "target"
                        :main artemis.test-runner
                        :optimizations :simple
                        :preloads []}}}}}})
