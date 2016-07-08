(ns web.users
  (:require [datomic.api :refer (tempid)]
            [cemerick.friend.credentials :as creds]))

(def schema
  [{:db/id          #db/id[:db.part/db]
    :db/ident       :user/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db.install/_attribute :db.part/db}

   {:db/id          #db/id[:db.part/db]
    :db/ident       :user/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db.install/_attribute :db.part/db}

   {:db/id          #db/id[:db.part/db]
    :db/ident       :user/x500-cn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/unique      :db.unique/value
    :db.install/_attribute :db.part/db}

   {:db/id          #db/id[:db.part/db]
    :db/ident       :user/bcrypt-passwd
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true
    :db.install/_attribute :db.part/db}

  {:db/id           #db/id[:db.part/db]
   :db/ident        :user/wbperson
   :db/valueType    :db.type/ref
   :db/cardinality  :db.cardinality/one
   :db.install/_attribute :db.part/db}

  {:db/id           #db/id[:db.part/db]
   :db/ident        :wormbase/curator
   :db/valueType    :db.type/ref
   :db/cardinality  :db.cardinality/one
   :db.install/_attribute :db.part/db}])

(defn add [name passwd]
  {:db/id               (tempid :db.part/user)
   :user/name           name
   :user/bcrypt-passwd  (creds/hash-bcrypt passwd)})
