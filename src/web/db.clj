(ns web.db
  (:require
   [datomic.api :as d]
   [environ.core :refer (env)]
   [mount.core :refer (defstate)]))

(defn- new-datomic-connection []
  (d/connect (datomic-uri)))

(defn- disconnect [conn]
  (d/release conn))

(defn datomic-uri []
  (env :trace-db))

(defstate datomic-conn
  :start (new-datomic-connection)
  :stop (disconnect datomic-conn))
