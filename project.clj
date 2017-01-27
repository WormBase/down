(defproject wormbase/down "0.1-SNAPSHOT"
  :dependencies
  [[base64-clj "0.1.1"]
   [bk/ring-gzip "0.2.1"]
   [cheshire "5.7.0"]
   [clj-http "3.4.1"]
   [clj-time "0.13.0"]
   [compojure "1.5.2"]
   [com.andrewmcveigh/cljs-time "0.4.0"]
   [com.cemerick/friend "0.2.3"]
   [com.ninjudd/ring-async "0.3.4"]
   [environ "1.1.0"]
   [fogus/ring-edn "0.3.0"]
   [friend-oauth2 "0.1.3"]
   [hiccup "1.0.5"]
   [mount "0.1.11"]
   [org.apache.httpcomponents/httpclient "4.5.3"]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "0.0-3308"]
   [org.clojure/data.csv "0.1.3"]
   [org.clojure/tools.cli "0.3.5"]
   [org.omcljs/om "0.9.0"]
   [prismatic/om-tools "0.4.0"]
   [ring "1.5.1"]
   [ring/ring-anti-forgery "1.0.1"]
   [ring/ring-jetty-adapter "1.5.1"]
   [secretary "1.2.3"]
   [wormbase/pseudoace "0.4.14"]]
  :description "WormBase Query and data exploration tools"
  :source-paths ["src"]
  :plugins [[lein-asset-minifier "0.3.0"]
            [lein-cljsbuild "1.1.3"]
            [lein-environ "1.1.0"]
            [lein-pprint "1.1.1"]
            [lein-ring "0.9.7"]]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :min-lein-version "2.0.0"
  :jvm-opts
  ["-Xmx6G"
   ;; same GC options as the transactor,
   "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
   ;; should minimize long pauses.
   "-Ddatomic.objectCacheMax=2500000000"
   "-Ddatomic.txTimeoutMsec=1000000"
   ;; Uncomment to prevent missing trace (HotSpot optimisation)
   ;; "-XX:-OmitStackTraceInFastThrow"
   ]
  :resource-paths ["resources"]
  :minify-assets
  {:dev
   {:assets
    {"resources/public/compiled/css/site.min.css"
     "resources/public/css/trace.css"}
     :options {:optimization :none}}
   :prod
   {:assets
    {"resources/public/compiled/css/site.min.css"
     "resources/public/css/trace.css"}
   :options {:optimization :advanced}}}
  :cljsbuild
  {:builds
   {:dev
    {:source-paths ["src"]
     :compiler
     {:optimizations :whitespace
      :pretty-print true
      :asset-path "compiled/js/out-dev"
      :output-dir "resources/public/compiled/js/out-dev"
      :output-to "resources/public/compiled/js/site.min.js"
      :source-map "resources/public/compiled/js/site.js.map"}}
    :prod
    {:source-paths ["src"]
     :compiler
     {:optimizations :simple
      :verbose false
      :pretty-print false
      :asset-path "compiled/js/out-prod"
      :output-dir "resources/public/compiled/js/out-prod"
      :output-to "resources/public/compiled/js/site.min.js"
      :source-map "resources/public/compiled/js/site.js.map"}}}}
  :clean-targets ^{:protect false} [:target-path
                                    :compile-path
                                    "resources/public/compiled/css"
                                    "resources/public/compiled/js"]
  :main ^:skip-aot web.core
  :ring {:init web.core/init
         :handler web.core/handler}
  :dev-dependencies [[acyclic/squiggly-clojure "0.1.6"]
                     [ring/ring-devel "1.5.0"]]
  :profiles {:datomic-pro
             [{:dependencies [[com.datomic/datomic-pro "0.9.5554"
                               :exclusions [joda-time]]]}]
             :ddb
             [{:dependencies
               [[com.amazonaws/aws-java-sdk-dynamodb "1.11.6"
                 :exclusions [joda-time]]]}]
             :dev [:ddb
                   :datomic-pro
                   {:plugins [[cider/cider-nrepl "0.15.0-SNAPSHOT"]
                              [jonase/eastwood "0.2.3"]
                              [lein-ancient "0.6.8"]
                              [refactor-nrepl "0.2.2"]]
                    :env {:trace-db "datomic:dev://localhost:4334/WS257"
                          :trace-require-login "0"
                          :squiggly "{:checkers [:eastwood]
                                     :eastwood-exclude-linters [:kibit]}"}
                    :repl {:plugins
                           [[cider/cider-nrepl "0.15.0-SNAPSHOT"]]
                           :dependencies
                           [[acyclic/squiggly-clojure "0.1.6"]
                            [org.clojure/tools.nrepl "0.2.12"]]}
                    :ring {:init web.core/init
                           :handler web.core/handler
                           :nrepl {:start? true}
                           :resource-paths ["test/resources"]}}]
             :prod [:ddb
                    :datomic-pro
                    {:env
                     {:trace-db "datomic:ddb://us-east-1/WS257/wormbase"
                      :trace-port "80"
                      :trace-require-login "0"}}]
             :uberjar [:prod {:aot :all}]})
