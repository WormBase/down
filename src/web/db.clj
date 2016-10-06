(ns web.db
  (:require
   [datomic.api :as d]
   [environ.core :refer (env)]
   [mount.core :refer (defstate)]))

(defn datomic-uri []
  (env :trace-db))

(defn- new-datomic-connection []
  (d/connect (datomic-uri)))

(defn- disconnect [conn]
  (d/release conn))

(defstate datomic-conn
  :start (new-datomic-connection)
  :stop (disconnect datomic-conn))
