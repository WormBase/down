(ns web.db
  (:require
   [clojure.string :as str]
   [datomic.api :as d]
   [environ.core :refer (env)]
   [mount.core :refer (defstate)]))

(defn datomic-uri []
  (env :trace-db))

(defn ws-version
  "Return the WormBase data version number from the datomic URI."
  []
  (if-let [db-uri (datomic-uri)]
    (->> (str/split db-uri #"/")
         (filter (partial re-matches #"WS\d+"))
         (first))))

(defn- new-datomic-connection []
  (d/connect (datomic-uri)))

(defn- disconnect [conn]
  (d/release conn))

(defstate datomic-conn
  :start (new-datomic-connection)
  :stop (disconnect datomic-conn))
