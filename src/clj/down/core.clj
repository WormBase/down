(ns down.core
  (:require
   [compojure.core :as cc :refer [GET POST]]
   [compojure.route :as route]
   [datomic.api :as d]
   [down.colonnade :refer [colonnade]]
   [down.db :refer [datomic-conn]]
   [down.edn :refer [wrap-edn-params-2]]
   [down.trace :as trace]
   [down.users :as users]
   [environ.core :refer [env]]
   [hiccup.core :refer [html]]
   [mount.core :as mount]
   [pseudoace.utils :refer [parse-int]]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.middleware.defaults :as ring-defaults]
   [ring.middleware.format :refer [wrap-restful-format]]
   [ring.middleware.gzip :refer [wrap-gzip]]
   [ring.util.http-response :as resp]
   [clojure.string :as str])
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

(defn init
  "Entry-point for ring web application."
  []
  (mount/start))

(defn wrap-db [handler]
  (fn [request]
    (let [req (assoc request :db (d/db datomic-conn) :con datomic-conn)]
      (handler req))))
    
(defn wrap-lookup-entity [handler]
  (fn [request]
    (let [db (:db request)
          cls (get-in request [:params :class])
          id (get-in request [:params :id])
          lookup-ref [(keyword cls "id") id]]
      (if (d/entid db (first lookup-ref))
        (if (d/entity db lookup-ref)
          (handler request)
          (resp/not-found "Invalid entity."))
        (resp/bad-request)))))

(def app-routes
  (cc/routes
   (GET "/" req
     (resp/found "/colonnade"))
   (GET "/raw2/:class/:id" {params :params db :db}
     (trace/get-raw-obj2
      db
      (:class params)
      (:id params)
      (parse-int-if (params "max-out"))
      (parse-int-if (params "max-in"))
      (= (params "txns") "true")))
   (GET "/attr2/:entid/:attrns/:attrname" {params :params db :db}
     (trace/get-raw-attr2
      db
      (Long/parseLong (:entid params))
      (str (:attrns params) "/" (:attrname params))
      (= (params "txns") "true")))
   (GET "/txns" {params :params db :db}
     (trace/get-raw-txns2
      db
      (let [ids (params :id)]
        (if (string? ids)
          [(Long/parseLong ids)]
          (map #(Long/parseLong %) ids)))))
   (GET "/history2/:entid/:attrns/:attrname" {params :params db :db}
     (trace/get-raw-history2
      db
      (Long/parseLong (:entid params))
      (keyword (.substring (:attrns params) 1) (:attrname params))))
   (GET "/ent/:id" {params :params db :db}
     (trace/get-raw-ent db (Long/parseLong (:id params))))
   (GET "/transaction-notes/:id" {{:keys [id]} :params db :db}
     (trace/get-transaction-notes db (Long/parseLong id)))
   (GET "/view/:class/:id" req
     (trace/viewer-page req))
   (GET "/gene-by-name/:name" {params :params db :db}
     (get-gene-by-name db (:name params)))
   (GET "/schema" {db :db}
     (trace/get-schema db))
   (cc/context "/colonnade" {db :db}
     (colonnade db))
   (route/resources "/")
   (route/not-found "These are not the worms you're looking for.")))

(def endpoint
  (-> app-routes
      (users/wrap-authentication)
      (wrap-db)
      (wrap-restful-format)
      (ring-defaults/wrap-defaults ring-defaults/site-defaults)
      (wrap-gzip)))

