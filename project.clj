(defproject wormbase/down "0.4-SNAPSHOT"
  :clean-targets ^{:protect false} [:target-path
                                    :compile-path
                                    "resources/public/compiled/css"
                                    "resources/public/compiled/js"]
  :clojurescript? true
  :cljsbuild
  {:builds
   {:dev
    {:source-paths ["src/cljs"]
     :compiler
     {:asset-path "compiled/js/out-dev"
      :optimizations :whitespace
      :output-dir "resources/public/compiled/js/out-dev"
      :output-to "resources/public/compiled/js/site.min.js"
      :pretty-print true
      :source-map "resources/public/compiled/js/site.js.map"}}
    :prod
    {:source-paths ["src/cljs"]
     :jar true
     :compiler
     {:asset-path "compiled/js/out-prod"
      :optimizations :advanced
      :output-dir "resources/public/compiled/js/out-prod"
      :output-to "resources/public/compiled/js/site.min.js"
      :pretty-print false
      :source-map "resources/public/compiled/js/site.js.map"
      :verbose false}}}}
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
   [metosin/ring-http-response "0.8.2"]
   [mount "0.1.11"]
   [org.apache.httpcomponents/httpclient "4.5.3"]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.9.229"]
   [org.clojure/data.csv "0.1.3"]
   [org.clojure/tools.cli "0.3.5"]
   [org.omcljs/om "0.9.0"]
   [prismatic/om-tools "0.4.0"]
   [ring "1.5.1"]
   [ring-middleware-format "0.7.2"]
   [ring/ring-anti-forgery "1.1.0-beta1"]
   [ring/ring-defaults "0.2.3"]
   [ring/ring-jetty-adapter "1.5.1"]
   [secretary "1.2.3"]
   [wormbase/pseudoace "0.4.14"]]
  :deploy-branches ["master"]
  :description "WormBase Query and data exploration tools"
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :jvm-opts
  [;; same GC options as the transactor,
   "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
   ;; should minimize long pauses.
   "-Ddatomic.objectCacheMax=2500000000"
   "-Ddatomic.txTimeoutMsec=1000000"
   ;; Uncomment to prevent missing trace (HotSpot optimisation)
   ;; "-XX:-OmitStackTraceInFastThrow"
   ]
  :min-lein-version "2.7.1"
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
  :main ^:skip-aot down.core
  :hooks [leiningen.cljsbuild]
  :plugins [[lein-asset-minifier "0.3.0"]
            [lein-cljsbuild "1.1.3"]
            [lein-environ "1.1.0"]
            [lein-pprint "1.1.1"]
            [lein-ring "0.9.7"]]
  :profiles {:datomic-pro
             {:dependencies
              [[com.datomic/datomic-pro "0.9.5554"
                :exclusions [com.google.guava/guava
                             joda-time]]
               [com.amazonaws/aws-java-sdk-dynamodb "1.11.6"
                :exclusions [joda-time]]]}
             :dev [:datomic-pro
                   {:plugins
                    [[jonase/eastwood "0.2.3"
                      :exclusions [org.clojure/clojure]]
                     [lein-ancient "0.6.8"]
                     [ring/ring-devel "1.5.1"]]
                    :env {:wb-db-uri "datomic:dev://localhost:4334/WS257"
                          :wb-require-login "0"}
                    :ring {:nrepl {:start? true}}
                    :resource-paths ["test/resources"]}]
             :uberjar
             {:aot :all
              :env {:wb-require-login "0"
                    :wb-db-uri "datomic:ddb://us-east-1/WS257/wormbase"}
              :hooks [minify-assets.plugin/hooks]
              :prep-tasks ["compile" ["cljsbuild" "once" "prod"]]}}
  :resource-paths ["resources"]
  :ring {:init down.core/init
         :handler down.core/endpoint}
  :uberjar-name "app.jar"
  :omit-source true
  :source-paths ["src/clj"]
  :target-path "target/%s")
