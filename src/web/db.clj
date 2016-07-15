(ns web.db
  (:require [datomic.api :as d]))

(defn get-db
  [uri]
  (let [con (d/connect uri)
        db (d/db con)]
    db))
