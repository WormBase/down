(ns web.common
  (:require
   [datomic.api :as d]
   [environ.core :refer [env]]
   [web.db :refer [ws-version]]))

(def head
  [:head
     [:link
      {:rel "stylesheet"
       :href (str "//maxcdn.bootstrapcdn.com/bootstrap"
                  "/3.3.0/css/bootstrap.min.css")}]
     [:link
      {:rel "stylesheet"
       :href (str "//maxcdn.bootstrapcdn.com/bootstrap"
                  "/3.3.0/css/bootstrap-theme.min.css")}]
     [:link
      {:rel "stylesheet"
       :href (str "//maxcdn.bootstrapcdn.com/font-awesome"
                  "/4.2.0/css/font-awesome.min.css")}]
     [:link
      {:rel "stylesheet" :href "/compiled/css/site.min.css"}]])

(defn identity-header
  [db]
  [:div.header-identity
   [:div {:style "display: inline-block"}
    [:img.banner {:src "/img/logo_wormbase_gradient_small.png"}]
    (if-let [name (:wormbase/system-name
                   (d/entity db :wormbase/system))]
      [:div.system-name name]
      [:div.system-name
       {:title (str "Datomic URI: " (env :wb-db-uri))}
        [:b (str "Data release:&nbsp;"
                 (ws-version))]])]])
