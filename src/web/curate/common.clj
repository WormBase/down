(ns web.curate.common
  (:use hiccup.core)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [ring.util.anti-forgery :refer [anti-forgery-field]]))

(defmulti lookup (fn [class db id] class))

(defmethod lookup :default [_ db id]
  [])

(defn lc
  "Convert `str` to all-lower-case."
  [^String str]
  (.toLowerCase str))

(def name-placeholders
  {"Gene"       "WBGene... or name"
   "Variation"  "WBVar... or name"
   "Feature"    "WBsf..."})

(defn ac-field
  "Return hiccup data corresponding to a namedb autocomplete field for
   names of domain `domain`."
  ([name domain]
     (ac-field domain ""))
  ([name domain value]
     [:input {:type "text"
              :name name
              :class "autocomplete"
              :data-domain domain
              :size 20
              :maxlength 20
              :autocomplete "off"
              :value (or value "")
              :placeholder (name-placeholders domain)}]))

(defn menu []
 (let [id friend/*identity*]
   (list 
    [:div.menu-header
     [:h3 "Curation tasks"]]

    [:div.menu-content

     [:h4 "General"]

     [:p [:a {:href "/curate/patch"} "Patch DB"]]
     [:p [:a {:href "/curate/txns"} "Transaction log"]]
     
     [:h4 "Gene"]
     
     [:p [:a {:href "/curate/gene/query"} "Find gene"]]
     
     (if true #_(authorized? #{:user.role/edit} id)
       [:p [:a {:href "/curate/gene/add-name"} "Add name"]])
     
     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/remove-gene-name"} "Remove name"]])    
    
     (if true #_(authorized? #{:user.role/edit} id)
       [:p [:a {:href "/curate/gene/new"} "New gene"]])

     (if true #_(authorized? #{:user.role/edit} id)
       [:p [:a {:href "/curate/gene/kill"} "Kill gene"]])

     (if true #_(authorized? #{:user.role/edit} id)
       [:p [:a {:href "/curate/gene/merge"} "Merge gene"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/split-gene"} "Split gene"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/load-gene-file"} "Load file"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/change-gene-class"} "Change class"]])

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/dump-genes"} "Dump all genes"]])


     [:h4 "Variation"]

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/query-variation"} "Find variation"]])

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/variation-last-id"} "Last variation ID"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-change-name"} "Change public name"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-request-id"} "Request new variation ID"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-kill"} "Kill variation ID"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/variation-merge"} "Merge two variation IDs"]])

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/dump-variations"} "Dump all variations"]])

     [:h4 "Feature"]

     (if (authorized? #{:user.role/view} id)
       [:p [:a {:href "/query-feature"} "Find feature"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/feature-new"} "New feature"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/feature-kill"} "Kill feature"]])

     (if (authorized? #{:user.role/edit} id)
       [:p [:a {:href "/feature-merge"} "Merge features"]])
     
     ]

     )))

(defn page [db & content]
  (html
   [:head
    [:title "Wormbase Curation System"]
    [:script {:language "javascript"
              :src "/js/nameserver.js"}]
    [:link {:rel "stylesheet"
            :href "/css/trace.css"}]
    [:link {:rel "stylesheet"
            :href "/css/curation.css"}]
    [:link {:rel "stylesheet"
            :href "//maxcdn.bootstrapcdn.com/font-awesome/4.3.0/css/font-awesome.min.css"}]]
   [:body
    [:div.root
     [:div.header
      [:div.header-identity
       [:div {:style "display: inline-block"}
        [:img.banner {:src "/img/logo_wormbase_gradient_small.png"}]
        (if-let [name (:wormbase/system-name (entity db :wormbase/system))]
          [:div.system-name name])]]
      [:div.header-main
       [:h1#page-title "Curation"]
       [:div.ident (:wbperson (friend/current-authentication))]]]
     [:div.container
      (vec (cons :div.content content))
      [:div.menu (menu)]]]]))

(defn txn-meta
  "Return a transaction metadata entity for the current request"
  []
  {:db/id (d/tempid :db.part/tx)
   :wormbase/curator [:person/id (:wbperson (friend/current-authentication))]})

(defmulti link (fn [domain _] domain))

(defmethod link "Gene" [_ id]
  [:span id
   [:a {:href (str "/view/gene/" id)}
    [:i {:class "fa fa-external-link"}]]])

(defmethod link "Variation" [_ id]
  [:span id
   [:a {:href (str "/curate/variation/query?lookup=" id)}
    [:i {:class "fa fa-external-link"}]]])

(defmethod link "Feature" [_ id]
  [:span id
   [:a {:href (str "/curate/feature/query?lookup=" id)}
    [:i {:class "fa fa-external-link"}]]])


