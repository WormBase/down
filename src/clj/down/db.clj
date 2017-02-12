(ns down.db
  (:require
   [clojure.string :as str]
   [datomic.api :as d]
   [environ.core :refer [env]]
   [mount.core :refer [defstate]]))

(defn ws-version
  "Return the WormBase data version number from the datomic URI."
  []
  (if-let [db-uri (env :wb-db-uri)]
    (->> (str/split db-uri #"/")
         (filter (partial re-matches #"WS\d+"))
         (first))))

(defn- new-datomic-connection []
  (d/connect (env :wb-db-uri)))

(defn- disconnect [conn]
  (d/release conn))

(defstate datomic-conn
  :start (new-datomic-connection)
  :stop (disconnect datomic-conn))
