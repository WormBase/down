(ns web.core
  (:require
   [base64-clj.core :as base64]
   [cemerick.friend.credentials :as creds]
   [cemerick.friend.workflows :as workflows]
   [cemerick.friend.util :as friend-util]
   [cemerick.friend :as friend]
   [cheshire.core :as json :refer (parse-string)]
   [clojure.string :as str]
   [clojure.walk]
   [compojure.core :refer (defroutes GET POST ANY context wrap-routes)]
   [compojure.handler :as handler]
   [compojure.route :as route]
   [datomic.api :as d]
   [environ.core :refer (env)]
   [friend-oauth2.util :refer (format-config-uri)]
   [friend-oauth2.workflow :as oauth2]
   [hiccup.core :refer (html)]
   [pseudoace.utils :refer (parse-int)]
   [ring.adapter.jetty :refer (run-jetty)]
   [ring.middleware.cookies :refer (wrap-cookies)]
   [ring.middleware.gzip]
   [ring.middleware.keyword-params :refer (wrap-keyword-params)]
   [ring.middleware.multipart-params :refer (wrap-multipart-params)]
   [ring.middleware.params :refer (wrap-params)]
   [ring.middleware.session :refer (wrap-session)]
   [ring.middleware.stacktrace :refer (wrap-stacktrace)]
   [ring.util.response :refer (redirect file-response)]
   [web.anti-forgery :refer (wrap-anti-forgery-ssl)]
   [web.colonnade :refer (colonnade post-query)]
   [web.curate.core :refer (curation-forms)]
   [web.curate.schema :refer (curation-schema curation-init curation-fns)]
   [web.edn :refer (wrap-edn-params-2)]
   [web.locatable-api :refer (feature-api)]
   [web.query :refer (post-query-restful)]
   [web.rest.gene :as gene]
   [web.rest.interactions :refer (get-interactions get-interaction-details)]
   [web.rest.references :refer (get-references)]
   [web.ssl :as ssl]
   [web.trace :as trace]
   [web.users :as users]
   [web.widgets :refer (gene-genetics-widget gene-phenotypes-widget)]))


(def uri (or (env :trace-db) "datomic:free://localhost:4334/wb248-imp1"))

(defn get-db
  ([]
   (get-db uri))
  ([db-uri]
   (let [con (d/connect db-uri)
         db (d/db con)]
     db)))

(def ^:private rules
  '[[[gene-name ?g ?n] [?g :gene/public-name ?n]]
    [[gene-name ?g ?n] [?c :gene.cgc-name/text ?n] [?g :gene/cgc-name ?c]]
    [[gene-name ?g ?n] [?g :gene/molecular-name ?n]]
    [[gene-name ?g ?n] [?g :gene/sequence-name ?n]]
    [[gene-name ?g ?n] [?o :gene.other-name/text ?n] [?g :gene/other-name ?o]]])

(defn get-gene-by-name [name]
  (let [ddb (get-db)
        genes (d/q '[:find ?gid
                     :in $ % ?name
                     :where (gene-name ?g ?name)
                     [?g :gene/id ?gid]]
                 ddb rules name)
        oldmems (d/q '[:find ?gcid
                       :in $ ?name
                       :where [?gc :gene-class/old-member ?name]
                              [?gc :gene-class/id ?gcid]]
                     ddb name)]
    (html
     [:h1 "Matches for " name]
     [:ul
      (for [[gid] genes]
        [:li
         [:a {:href (str "/view/gene/" gid)} gid]])]
     (when-let [o (seq oldmems)]
       [:div
        [:h1 "Old member of..."]
        [:ul
         (for [[gcid] o]
           [:a {:href (str "/view/gene-class/" gcid)} gcid])]]))))


(defn parse-int-if [s]
  (if s
    (Integer/parseInt s)))


(defroutes routes
  (GET "/" [] "hello")
  (friend/logout (ANY "/logout" [] (redirect "/")))
  (GET "/raw2/:class/:id" {params :params db :db}
       (trace/get-raw-obj2
        db
        (:class params)
        (:id params)
        (parse-int-if (params "max-out"))
        (parse-int-if (params "max-in"))
        (= (params "txns") "true")))
  (GET "/attr2/:entid/:attrns/:attrname" {params :params}
       (trace/get-raw-attr2
        (get-db)
        (Long/parseLong (:entid params))
        (str (:attrns params) "/" (:attrname params))
        (= (params "txns") "true")))
  (GET "/txns" {params :params}
       (trace/get-raw-txns2
        (get-db)
        (let [ids (params :id)]
          (if (string? ids)
            [(Long/parseLong ids)]
            (map #(Long/parseLong %) ids)))))
  (GET "/history2/:entid/:attrns/:attrname" {params :params}
       (trace/get-raw-history2
        (get-db)
        (Long/parseLong (:entid params))
        (keyword (.substring (:attrns params) 1) (:attrname params))))
  (GET "/ent/:id" {params :params db :db}
       (trace/get-raw-ent db (Long/parseLong (:id params))))
  (GET "/transaction-notes/:id" {{:keys [id]} :params
                                 db :db}
       (trace/get-transaction-notes db (Long/parseLong id)))
  (GET "/view/:class/:id" req (trace/viewer-page req))
  (GET "/gene-by-name/:name" {params :params}
       (get-gene-by-name (:name params)))

  (GET "/gene-phenotypes/:id" {params :params}
       (gene-phenotypes-widget (get-db) (:id params)))
  (GET "/gene-genetics/:id" {params :params}
       (gene-genetics-widget (get-db) (:id params)))

  (GET "/rest/widget/gene/:id/overview" {params :params}
       (gene/overview (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/history" {params :params}
       (gene/history (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/phenotype" {params :params}
       (gene/phenotypes (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/interactions" {params :params}
       (get-interactions "gene" (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/interaction_details" {params :params}
       (get-interaction-details "gene" (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/mapping_data" {params :params}
       (gene/mapping-data (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/human_diseases" {params :params}
       (gene/human-diseases (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/references" {params :params}
       (get-references "gene" (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/reagents" {params :params}
       (gene/reagents (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/gene_ontology" {params :params}
       (gene/gene-ontology (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/expression" {params :params}
       (gene/expression (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/homology" {params :params}
       (gene/homology (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/sequences" {params :params}
       (gene/sequences (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/feature" {params :params}
       (gene/features (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/genetics" {params :params}
       (gene/genetics (get-db) (:id params)))
  (GET "/rest/widget/gene/:id/external_links" {params :params}
       (gene/external-links (get-db) (:id params)))

  (context "/features" [] feature-api)

  (GET "/prefix-search" {params :params}
       (trace/get-prefix-search
        (get-db)
        (params "class")
        (params "prefix")))
  (GET "/schema" {db :db} (trace/get-schema db))
  (GET "/rest/auth" [] "hello")

  (POST "/transact" req
        (friend/authorize #{::user}
          (d/transact req)))
  (context "/colonnade" req (friend/authorize #{::user}
                              (colonnade (get-db))))

  (context "/curate" req (friend/authorize #{::user}
                          (if (env :trace-enable-curation-forms)
                            curation-forms
                            (GET "/*" [] "Curation disabled on this server"))))

  (route/files "/" {:root "resources/public"}))

(defroutes api-routes
  (let [con (d/connect uri)]
    (POST
     "/api/query"
     {params :params}
     (if (env :trace-accept-rest-query)
       (post-query-restful con params)))))

(defn wrap-db [handler]
  (fn [request]
    (let [con (d/connect uri)]
      (handler (assoc request :con con :db (d/db con))))))

(defn- goog-credential-fn [token]
  (if-let [u (d/entity (get-db) [:user/email (:id (:access-token token))])]
    {:identity token
     :email (:user/email u)
     :wbperson (:person/id (:user/wbperson u))
     :roles #{::user}}))

(defn- ssl-credential-fn [{:keys [ssl-client-cert]}]
  (if-let [u (d/entity
              (get-db)
              [:user/x500-cn (->> (.getSubjectX500Principal ssl-client-cert)
                                  (.getName)
                                  (re-find #"CN=([^,]+)")
                                  (second))])]
    {:identity ssl-client-cert
     :wbperson (:person/id (:user/wbperson u))
     :roles #{::user}}))

(def client-config {:client-id      (env :trace-oauth2-client-id)
                    :client-secret  (env :trace-oauth2-client-secret)
                    :callback {:domain (or (env :trace-oauth2-redirect-domain)
                                           "http://127.0.0.1:8130")
                               :path "/oauth2callback"}})


(def uri-config
  {:authentication-uri {:url "https://accounts.google.com/o/oauth2/auth"
                        :query {:client_id (:client-id client-config)
                               :response_type "code"
                               :redirect_uri (format-config-uri client-config)
                               :scope "email"}}

   :access-token-uri {:url "https://accounts.google.com/o/oauth2/token"
                      :query {:client_id (:client-id client-config)
                              :client_secret (:client-secret client-config)
                              :grant_type "authorization_code"
                              :redirect_uri (format-config-uri client-config)}}})

(defn- flex-decode [s]
  (let [m (mod (count s) 4)
        s (if (pos? m)
            (str s (.substring "====" m))
            s)]
    (base64/decode s)))


(defn- goog-token-parse [resp]
  (let [token     (parse-string (:body resp) true)
        id-token  (parse-string
                   (flex-decode
                    (second
                     (str/split (:id_token token) #"\.")))
                   true)]
    {:access_token (:access_token token)
     :id (:email id-token)}))


(def secure-app
  (let [allow-annon? ((complement boolean) (env :trace-require-login))]
    (-> (compojure.core/routes
         (wrap-routes routes wrap-anti-forgery-ssl)
         api-routes)
        (friend/authenticate {:allow-anon? allow-annon?
                              :workflows [(ssl/client-cert-workflow
                                           :credential-fn ssl-credential-fn)
                                          (oauth2/workflow
                                           {:client-config client-config
                                            :uri-config uri-config
                                            :access-token-parsefn goog-token-parse
                                            :credential-fn goog-credential-fn})]})
        wrap-db
        wrap-edn-params-2
        wrap-keyword-params
        wrap-params
        wrap-multipart-params
        wrap-stacktrace
        wrap-session
        wrap-cookies)))


(def trace-port (let [p (env :trace-port)]
                  (cond
                   (integer? p)  p
                   (string? p)   (parse-int p)
                   :default      8120)))

(def trace-ssl-port (let [p (env :trace-ssl-port)]
                      (cond
                        (integer? p)   p
                        (string? p)    (parse-int p))))

(def keystore (env :trace-ssl-keystore))
(def keypass  (env :trace-ssl-password))

(defn setup-users-schema
  "Setup the users' schema."
  []
  (let [con (d/connect uri)]
    @(d/transact con users/schema)
    @(d/transact con (butlast curation-schema))
    @(d/transact con (butlast curation-init))
    @(d/transact con (butlast curation-fns))))

(defn -main
  [& args]
  (defonce server
    (if trace-ssl-port
      (run-jetty #'secure-app {:port trace-port
                               :join? false
                               :ssl-port trace-ssl-port
                               :keystore keystore
                               :key-password keypass
                               :truststore keystore
                               :trust-password keypass
                               :client-auth :want})
      (run-jetty #'secure-app {:port trace-port
                               :join? false}))))
