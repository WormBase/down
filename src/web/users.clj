(ns web.users
  (:require
   [base64-clj.core :as base64]
   [cemerick.friend :as friend]
   [cemerick.friend.credentials :as creds]
   [cemerick.friend.workflows :refer [make-auth]]
   [cheshire.core :as json :refer [parse-string]]
   [clojure.string :as str]
   [datomic.api :as d]
   [environ.core :refer [env]]
   [friend-oauth2.util :refer [format-config-uri]]
   [friend-oauth2.workflow :as oauth2]
   [web.util :refer [allow-anonymous?]]))

(def schema
  [{:db/id          (d/tempid :db.part/db)
    :db/ident       :user/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db.install/_attribute :db.part/db}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :user/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db.install/_attribute :db.part/db}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :user/x500-cn
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/unique      :db.unique/value
    :db.install/_attribute :db.part/db}

   {:db/id          (d/tempid :db.part/db)
    :db/ident       :user/bcrypt-passwd
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true
    :db.install/_attribute :db.part/db}

  {:db/id           (d/tempid :db.part/db)
   :db/ident        :user/wbperson
   :db/valueType    :db.type/ref
   :db/cardinality  :db.cardinality/one
   :db.install/_attribute :db.part/db}

  {:db/id           (d/tempid :db.part/db)
   :db/ident        :wormbase/curator
   :db/valueType    :db.type/ref
   :db/cardinality  :db.cardinality/one
   :db.install/_attribute :db.part/db}])

(defn add [username passwd]
  {:db/id               (d/tempid :db.part/user)
   :user/name           username
   :user/bcrypt-passwd  (creds/hash-bcrypt passwd)})

(defn setup-schema
  "Setup the users' schema."
  [uri]
  (let [con (d/connect uri)]
    @(d/transact con schema)
    @(d/transact con (butlast curation-schema))
    @(d/transact con (butlast curation-init))
    @(d/transact con (butlast curation-fns))))

(defn- flex-decode [s]
  (let [m (mod (count s) 4)
        s (if (pos? m)
            (str s (.substring "====" m))
            s)]
    (base64/decode s)))

(def client-config
  {:client-id      (env :trace-oauth2-client-id)
   :client-secret  (env :trace-oauth2-client-secret)
   :callback {:domain (or (env :trace-oauth2-redirect-domain)
                          "http://localhost:8130")
              :path "/oauth2callback"}})

(def uri-config
  {:authentication-uri
   {:url "https://accounts.google.com/o/oauth2/auth"
    :query {:client_id (:client-id client-config)
            :response_type "code"
            :redirect_uri (format-config-uri client-config)
            :scope "email"}}
   :access-token-uri
   {:url "https://accounts.google.com/o/oauth2/token"
    :query {:client_id (:client-id client-config)
            :client_secret (:client-secret client-config)
            :grant_type "authorization_code"
            :redirect_uri (format-config-uri client-config)}}})

(defn goog-credential-fn [{:keys [db token] :as creds}]
  (if-let [user (d/entity db [:user/email (:id (:access-token token))])]
    (make-auth {:identity token
                :email (:user/email user)
                :wbperson (:person/id (:user/wbperson user))
                :roles #{::user}})))

(defn goog-token-parse [resp]
  (let [token     (parse-string (:body resp) true)
        id-token  (parse-string
                   (flex-decode
                    (second
                     (str/split (:id_token token) #"\.")))
                   true)]
    {:access_token (:access_token token)
     :id (:email id-token)}))

(defn authorize-maybe [handler]
  (if (allow-anonymous?)
    handler
    (friend/authorize #{::user} (handler))))

(defn make-workflow [db client-config uri-config]
  (oauth2/workflow
   {:client-config client-config
    :uri-config uri-config
    :access-token-parsefn goog-token-parse
    :credential-fn (partial goog-credential-fn db)}))

(defn authenticate* [db]
  (let [allow-anon? (allow-anonymous?)]
    #(friend/authenticate
      %
      {:allow-anon? allow-anon?
       :workflows [(make-workflow db client-config uri-config)]})))

(defn make-authenticator [db]
  (authenticate* db))
