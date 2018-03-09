(defproject artemis "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.946"]
                 [alumbra/parser "0.1.7"]
                 [alumbra/errors "0.1.1"]
                 [cljs-http "0.1.44"]
                 [figwheel-sidecar "0.5.0"]
                 [binaryage/devtools "0.9.9"]]
  :plugins [[lein-figwheel "0.5.14"]
            [lein-cljsbuild "1.1.7"]
            [lein-doo "0.1.8"]]
  :aliases {"demo" ["with-profile" "+demo" "figwheel"]
            "test" ["test-jvm"]
            "test-chrome" ["with-profile" "test" "doo" "chrome" "test" "once"]
            "test-jvm" ["with-profile" "test" "doo" "rhino" "test" "once"]}
  :clean-targets ^{:protect false} [:target-path "resources/js"]
  :figwheel
  {:server-port 8000
   :http-server-root "."}

  :cljsbuild
  {:builds
   {:dev
    {:source-paths ["src"]
     :figwheel true
     :compiler {:output-to "resources/js/main.js"
                :output-dir "resources/js"
                :asset-path "js/"
                :main artemis.core
                :preloads [artemis.preloads]
                :optimizations :none}}}}
  :profiles
  {:demo  {:figwheel
           {:server-port 8000
            :http-server-root "."}
           :cljsbuild
           {:builds
            {:dev
             {:source-paths ["src" "demo"]
              :figwheel true
              :compiler {:output-to "resources/js/main.js"
                         :output-dir "resources/js"
                         :asset-path "js/"
                         :main artemis.demo
                         :optimizations :none}}}}}
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
