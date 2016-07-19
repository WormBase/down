(defproject wormbase/trace "0.1-SNAPSHOT"
  :repositories {"my.datomic.com" {:url "https://my.datomic.com/repo"
                                   :creds :gpg}}
  :dependencies
  [[base64-clj "0.1.1"]
   [bk/ring-gzip "0.1.1"]
   [cheshire "5.6.3"]
   [clj-http "3.1.0"]
   [clj-time "0.12.0"]
   [compojure "1.5.1"]
   [com.andrewmcveigh/cljs-time "0.4.0"]
   [com.cemerick/friend "0.2.3"]
   ;; [com.datomic/datomic-free "0.9.5359" :exclusions [joda-time]]
   [com.datomic/datomic-pro "0.9.5359"
    :exclusions [joda-time]]
   [com.ninjudd/ring-async "0.3.4"]
   [environ "1.0.3"]
   [fogus/ring-edn "0.3.0"]
   [friend-oauth2 "0.1.3"]
   [hiccup "1.0.5"]
   [org.apache.httpcomponents/httpclient "4.5.2"]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "0.0-3308"]
   [org.clojure/data.csv "0.1.3"]
   [org.clojure/tools.cli "0.3.5"]
   [org.omcljs/om "0.9.0"]
   [prismatic/om-tools "0.4.0"]
   [ring "1.5.0"]
   [ring/ring-anti-forgery "1.0.1"]
   [secretary "1.2.3"]
   [wormbase/pseudoace "0.4.9"]
   ;; Required?
   ;; [org.clojure/tools.cli "0.3.3"]
   ;; [org.clojars.hozumi/clj-commons-exec "1.0.6" ]
   ]
  :description "WormBase curation tools"
  :source-paths ["src"]
  :plugins [[lein-cljsbuild "1.1.3"]
            [lein-pprint "1.1.1"]
            [lein-ring "0.9.7"]]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :license "GPLv2"
  :min-lein-version "2.0.0"
  :jvm-opts ["-Xmx6G"
             ;; same GC options as the transactor,
             "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
             ;; should minimize long pauses.
             "-Ddatomic.objectCacheMax=2500000000"
             "-Ddatomic.txTimeoutMsec=1000000"
             ;; Uncomment to prevent missing trace (HotSpot optimisation)
             ;; "-XX:-OmitStackTraceInFastThrow"
             ]
  :env {:trace-db "datomic:free://localhost:4334/WS254"
        :trace-user-db "datomic:free://localhost:4334/users"
        :trace-port "8130"
        :trace-accept-rest-query "1"}
  :resource-paths ["resources"]
  :cljsbuild
  {:builds [{:id "dev"
             :source-paths ["src"]
             :compiler {
                        :optimizations :whitespace
                        :output-to "resources/public/js/main.js"
                        :output-dir "resources/public/js/out"
                        :source-map "resources/public/js/main.js.map"}}]}
  :main web.core
  :ring {:handler web.core/secure-app}
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[datomic-schema-grapher "0.0.1"]]
                   :plugins [[jonase/eastwood "0.2.3"]
                             [lein-ancient "0.6.8"]
                             [lein-bikeshed "0.3.0"]
                             [lein-kibit "0.1.2"]
                             [lein-ns-dep-graph "0.1.0-SNAPSHOT"]]
                   :resource-paths ["test/resources"]}})
