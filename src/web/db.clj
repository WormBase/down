(ns web.db
  (:require
   [datomic.api :as d]
   [environ.core :refer (env)]
   [mount.core :refer (defstate)]))

(defn- new-datomic-connection []
  (d/connect (uri)))

(defn- disconnect [conn]
  (d/release conn))

(defn uri []
  (env :trace-db))

(defstate datomic-conn
  :start (new-datomic-connection)
  :stop (disconnect datomic-conn))
