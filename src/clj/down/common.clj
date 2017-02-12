(ns down.common
  (:require
   [datomic.api :as d]
   [down.db :refer [ws-version]]
   [environ.core :refer [env]]))

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
   [:div 
    [:a.logo-link
     {:href "/"
      :title "Colonnade"}
     [:img.banner
      {:src "/img/logo_wormbase_gradient_small.png"}]]
    (if-let [name (:wormbase/system-name
                   (d/entity db :wormbase/system))]
      [:div.system-name name]
      [:div.system-name
       {:title (str "Datomic URI: " (env :wb-db-uri))}
       [:span
        [:b (str "Release:&nbsp;" (ws-version))]]])]])
