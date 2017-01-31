(ns web.core
  (:require
   [cemerick.friend :as friend]
   [clojure.string :as str]
   [clojure.walk]
   [compojure.core :refer [routes GET POST ANY context wrap-routes]]
   [compojure.route :as route]
   [datomic.api :as d]
   [environ.core :refer [env]]
   [hiccup.core :refer [html]]
   [mount.core :as mount]
   [pseudoace.utils :refer [parse-int]]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
   [ring.middleware.gzip :refer [wrap-gzip]]
   [ring.util.response :refer [redirect file-response]]
   [web.colonnade :refer [colonnade post-query]]
   [web.db :refer [datomic-conn]]
   [web.edn :refer [wrap-edn-params-2]]
   [web.trace :as trace]
   [web.users :as users])
  (:gen-class))

(def ^:private rules
  '[[[gene-name ?g ?n] [?g :gene/public-name ?n]]
    [[gene-name ?g ?n]
     [?c :gene.cgc-name/text ?n]
     [?g :gene/cgc-name ?c]]
    [[gene-name ?g ?n] [?g :gene/molecular-name ?n]]
    [[gene-name ?g ?n] [?g :gene/sequence-name ?n]]
    [[gene-name ?g ?n]
     [?o :gene.other-name/text ?n]
     [?g :gene/other-name ?o]]])

(defn get-gene-by-name [db nam]
  (let [genes (d/q '[:find ?gid
                     :in $ % ?name
                     :where (gene-name ?g ?name)
                     [?g :gene/id ?gid]]
                   db rules nam)
        oldmems (d/q '[:find ?gcid
                       :in $ ?name
                       :where [?gc :gene-class/old-member ?name]
                              [?gc :gene-class/id ?gcid]]
                     db nam)]
    (html
     [:h1 "Matches for " nam]
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

(defn app-routes [db]
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
      db
      (Long/parseLong (:entid params))
      (str (:attrns params) "/" (:attrname params))
           (= (params "txns") "true")))
   (GET "/txns" {params :params}
     (trace/get-raw-txns2
      db
      (let [ids (params :id)]
        (if (string? ids)
          [(Long/parseLong ids)]
          (map #(Long/parseLong %) ids)))))
   (GET "/history2/:entid/:attrns/:attrname" {params :params}
     (trace/get-raw-history2
      db
      (Long/parseLong (:entid params))
      (keyword (.substring (:attrns params) 1) (:attrname params))))
   (GET "/ent/:id" {params :params db :db}
     (trace/get-raw-ent db (Long/parseLong (:id params))))
   (GET "/transaction-notes/:id" {{:keys [id]} :params
                                  db :db}
     (trace/get-transaction-notes db (Long/parseLong id)))
   (GET "/view/:class/:id" req (trace/viewer-page req))
   (GET "/gene-by-name/:name" {params :params}
     (get-gene-by-name db (:name params)))
   (GET "/schema" {db :db} (trace/get-schema db))
   (POST "/transact" req
     (friend/authorize #{::user}
                       (d/transact (d/connect (datomic-uri)) req)))
   (context "/colonnade" req (colonnade db))
   (route/files "/" {:root "resources/public"})))

(defn init
  "Entry-point for ring web application."
  []
  (mount/start))

(defn handler [request]
  (let [db (d/db datomic-conn)
        request (assoc request :db db :con datomic-conn)
        authenticate (users/make-authenticator db)
        handle (->
                (routes
                 (wrap-routes (app-routes db) wrap-anti-forgery-ssl))
                authenticate
                wrap-edn-params-2
                wrap-keyword-params
                wrap-params
                wrap-multipart-params
                wrap-stacktrace
                wrap-session
                wrap-cookies)]
    (handle request)))

(defn- get-port [env-key & {:keys [default]
                            :or {default nil}}]
  (let [p (env env-key)]
    (cond
      (integer? p) p
      (string? p)  (parse-int p)
      :default default)))

(def trace-port (get-port :trace-port :default 8120))

(def trace-ssl-port (get-port :trace-ssl-port))

(defn -main
  [& args]
  (let [handler* handler
        server (if trace-ssl-port
                 (run-jetty #'handler {:port trace-port
                                       :join? false
                                       :ssl-port trace-ssl-port
                                       :client-auth :want})
                 (run-jetty #'handler {:port trace-port
                                       :join? false}))]
    server))
