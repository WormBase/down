(ns web.core
  (:require
   [cemerick.friend :as friend]
   [clojure.string :as str]
   [clojure.walk]
   [compojure.core :refer (routes GET POST ANY context wrap-routes)]
   [compojure.handler :as handler]
   [compojure.route :as route]
   [datomic.api :as d]
   [environ.core :refer (env)]
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
   [web.db :refer (get-db)]
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

(def uri (env :trace-db))

(def ^:private rules
  '[[[gene-name ?g ?n] [?g :gene/public-name ?n]]
    [[gene-name ?g ?n] [?c :gene.cgc-name/text ?n] [?g :gene/cgc-name ?c]]
    [[gene-name ?g ?n] [?g :gene/molecular-name ?n]]
    [[gene-name ?g ?n] [?g :gene/sequence-name ?n]]
    [[gene-name ?g ?n] [?o :gene.other-name/text ?n] [?g :gene/other-name ?o]]])

(defn get-gene-by-name [name]
  (let [ddb (get-db uri)
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

(defn app-routes []
  (routes
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
      (get-db uri)
      (Long/parseLong (:entid params))
      (str (:attrns params) "/" (:attrname params)
           (= (params "txns") "true"))))
   (GET "/txns" {params :params}
     (trace/get-raw-txns2
      (get-db uri)
      (let [ids (params :id)]
        (if (string? ids)
          [(Long/parseLong ids)]
          (map #(Long/parseLong %) ids)))))
   (GET "/history2/:entid/:attrns/:attrname" {params :params}
     (trace/get-raw-history2
      (get-db uri)
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
     (gene-phenotypes-widget (get-db uri) (:id params)))
   (GET "/gene-genetics/:id" {params :params}
     (gene-genetics-widget (get-db uri) (:id params)))

   (context "/rest/widget/gene/:id" {params :params}
     (GET "/overview" []
       (gene/overview (get-db uri) (:id params)))
     (GET "/history" []
       (gene/history (get-db uri) (:id params)))
     (GET "/phenotype" []
       (gene/phenotypes (get-db uri) (:id params)))
     (GET "/interactions" []
       (get-interactions "gene" (get-db uri) (:id params)))
     (GET "/interaction_details" []
       (get-interaction-details "gene" (get-db uri) (:id params)))
     (GET "/mapping_data" []
       (gene/mapping-data (get-db uri) (:id params)))
     (GET "/human_diseases" []
       (gene/human-diseases (get-db uri) (:id params)))
     (GET "/references" []
       (get-references "gene" (get-db uri) (:id params)))
     (GET "/reagents" []
       (gene/reagents (get-db uri) (:id params)))
     (GET "/gene_ontology" []
       (gene/gene-ontology (get-db uri) (:id params)))
     (GET "/expression" []
       (gene/expression (get-db uri) (:id params)))
     (GET "/homology" []
       (gene/homology (get-db uri) (:id params)))
     (GET "/seqeuences" []
       (gene/sequences (get-db uri) (:id params)))
     (GET "/feature" []
       (gene/features (get-db uri) (:id params)))
     (GET "/genetics" []
       (gene/genetics (get-db uri) (:id params)))
     (GET "/external_links" []
       (gene/external-links (get-db uri) (:id params))))
   (context "/features" [] feature-api)
   (GET "/prefix-search" {params :params}
     (trace/get-prefix-search
      (get-db uri)
      (params "class")
      (params "prefix")))
   (GET "/schema" {db :db} (trace/get-schema db))
   (GET "/rest/auth" [] "hello")
   (POST "/transact" req
     (friend/authorize #{::user}
                       (d/transact (d/connect uri) req)))
   (context "/colonnade" req (colonnade (get-db uri)))
   (context "/curate" req (friend/authorize
                           #{::user}
                           (if (env :trace-enable-curation-forms)
                             curation-forms
                             (GET "/*" [] "Curation disabled on this server"))))
   (route/files "/" {:root "resources/public"})))

(defn api-routes []
  (let [con (d/connect uri)]
    (routes
     (POST
         "/api/query"
         {params :params}
       (if (env :trace-accept-rest-query)
         (post-query-restful con params))))))

(defn wrap-db [handler]
  (fn [request]
    (let [con (d/connect uri)]
      (handler (assoc request :con con :db (d/db con))))))

(defn secure-app [request]
  (let [authenticate (partial users/authenticate uri)
        handler (-> (routes
                     (wrap-routes (app-routes) wrap-anti-forgery-ssl)
                     (api-routes))
                    authenticate
                    wrap-db
                    wrap-edn-params-2
                    wrap-keyword-params
                    wrap-params
                    wrap-multipart-params
                    wrap-stacktrace
                    wrap-session
                    wrap-cookies)]
    (handler request)))

(defn- get-port [env-key & {:keys [default]
                            :or {default nil}}]
  (let [p (env env-key)]
    (cond
      (integer? p) p
      (string? p)  (parse-int p)
      :default default)))

(def trace-port (get-port :trace-port :default 8120))

(def trace-ssl-port (get-port :trace-ssl-port))

;; (defonce server
;;   (if trace-ssl-port
;;     (run-jetty #'secure-app {:port trace-port
;;                              :join? false
;;                              :ssl-port trace-ssl-port
;;                              :client-auth :want})
;;     (run-jetty #'secure-app {:port trace-port
;;                              :join? false})))

;; (defn -main
;;   [& args]
;;   (.stop server)
;;   (.start server))
