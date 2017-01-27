(ns web.common
  (:require
   [datomic.api :as d]
   [web.db :refer (datomic-uri ws-version)]))

(defn identity-header
  [db]
  [:div.header-identity
   [:div {:style "display: inline-block"}
    [:img.banner {:src "/img/logo_wormbase_gradient_small.png"}]
    (if-let [name (:wormbase/system-name
                   (d/entity db :wormbase/system))]
      [:div.system-name name]
      [:div.system-name
       {:title (str "Datomic URI: "
                    (datomic-uri))}
        [:b (str "Data release:&nbsp;"
                 (ws-version))]])]])
