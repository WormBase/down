;;; TODO
;;;
;;; Code:
;;; Check for broken urls
;;; Remove dead code
;;; Run eastwood
;;; Consider changing all app namespaces
;;; Release:
;;; Test Docker container with eb local
;;; Re-instatate EB environment (create new?)
;;; Delete unused ECR repositories/images
(defproject wormbase/down "0.1-SNAPSHOT"
  :clean-targets ^{:protect false} [:target-path
                                    :compile-path
                                    "resources/public/compiled/css"
                                    "resources/public/compiled/js"]
  :cljsbuild
  {:builds
   {:dev
    {:source-paths ["src"]
     :compiler
     {:asset-path "compiled/js/out-dev"
      :optimizations :whitespace
      :output-dir "resources/public/compiled/js/out-dev"
      :output-to "resources/public/compiled/js/site.min.js"
      :pretty-print true
      :source-map "resources/public/compiled/js/site.js.map"}}
    :prod
    {:source-paths ["src"]
     :compiler
     {:asset-path "compiled/js/out-prod"
      :jar true
      :optimizations :advanced
      :output-dir "resources/public/compiled/js/out-prod"
      :output-to "resources/public/compiled/js/site.min.js"
      :pretty-print false
      :source-map "resources/public/compiled/js/site.js.map"
      :verbose false}}}}
  ;; :compile-path "%s/aot-files"
  :verbose true
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
   [ring/ring-defaults "0.2.3"]
   [ring/ring-anti-forgery "1.0.1"]
   [ring/ring-jetty-adapter "1.5.1"]
   [secretary "1.2.3"]
   [wormbase/pseudoace "0.4.14"]]
  :deploy-branches ["master"]
  :description "WormBase Query and data exploration tools"
  :javac-options ["-target" "1.8" "-source" "1.8"]
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
  :min-lein-version "2.6.1"
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
  :main ^:skip-aot web.core
  :plugins [[lein-asset-minifier "0.3.0"]
            [lein-cljsbuild "1.1.3"]
            [lein-environ "1.1.0"]
            [lein-pprint "1.1.1"]
            [lein-ring "0.9.7"]]
  :profiles {:datomic-pro
             [{:dependencies [[com.datomic/datomic-pro "0.9.5554"
                               :exclusions [joda-time]]]}]
             :ddb
             [{:dependencies
               [[com.amazonaws/aws-java-sdk-dynamodb "1.11.6"
                 :exclusions [joda-time]]]}]
             :dev [:ddb
                   :datomic-pro
                   {:plugins [[jonase/eastwood "0.2.3"
		               :exclusions [org.clojure/clojure]]
                              [lein-ancient "0.6.8"]
                              [ring/ring-devel "1.5.1"]]
                    :env {:wb-db-uri "datomic:dev://localhost:4334/WS257"
                          :trace-require-login "0"
                          :squiggly "{:checkers [:eastwood]}"}
                    :ring {:init web.core/init
                           :handler web.core/handler
                           :nrepl {:start? true}
                           :resource-paths ["test/resources"]}}]
             :prod [:ddb
                    :datomic-pro
                    {:env
                     {:wb-db-uri "datomic:ddb://us-east-1/WS257/wormbase"
                      :trace-require-login "0"}}]
             :uberjar
             {:aot :all
              :hooks [minify-assets.plugin/hooks]
              :omit-source true
              :prep-tasks ["compile" ["cljsbuild" "once" "prod"]]}}
  :resource-paths ["resources"]
  :ring {:init web.core/init
         :handler web.core/handler}
  :source-paths ["src"]
  :target-path "target/%s/")
