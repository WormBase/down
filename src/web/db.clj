(ns web.db
  (:require
   [datomic.api :as d]
   [environ.core :refer (env)]
   [mount.core :refer (defstate)]))

(def uri (env :trace-db))

(defn- new-datomic-connection [uri]
  (d/connect uri))

(defn- disconnect [conn]
  (d/release conn))

(defstate datomic-conn
  :start (new-datomic-connection uri)
  :stop (disconnect datomic-conn))
