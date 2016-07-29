(ns web.colonnade
  (:require [datomic.api :as d :refer (history q touch entity)]
            [clojure.string :as str]
            [ring.adapter.jetty :refer (run-jetty)]
            [compojure.core :refer (defroutes GET POST routes)]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [hiccup.core :refer (html)]
            [clojure.edn :as edn]
            [clojure.data.csv :refer (write-csv)]
            [pseudoace.acedump :as acedump]
            [pseudoace.utils :refer (sort-by-cached)]
            [web.anti-forgery :refer (*anti-forgery-token*)]))

(defn- page [{:keys [db] :as req}]
  (html
   [:html
    [:head
     [:link
      {:rel "stylesheet"
       :href "//maxcdn.bootstrapcdn.com/bootstrap/3.3.0/css/bootstrap.min.css"}]
     [:link
      {:rel "stylesheet"
       :href "//maxcdn.bootstrapcdn.com/bootstrap/3.3.0/css/bootstrap-theme.min.css"}]
     [:link
      {:rel "stylesheet"
       :href "//maxcdn.bootstrapcdn.com/font-awesome/4.2.0/css/font-awesome.min.css"}]
     [:link
      {:rel "stylesheet"
       :href "/css/trace.css"}]]
    [:body
     [:div.root
      [:div.header
       [:div.header-identity
        [:div {:style "display: inline-block"}
         [:img.banner {:src "/img/logo_wormbase_gradient_small.png"}]
         (if-let [name (:wormbase/system-name (entity db :wormbase/system))]
           [:div.system-name name])]]
       [:div.header-main
        [:h1#page-title "Colonnade"]
        [:img.banner {:src "/img/kazannevsky.jpg" :width 130 :style "float: right"}]]
       [:div#header-content]]
     [:div.container-fluid
      [:div#table-maker]]]
     [:script {:src "/js/out/goog/base.js"
               :type "text/javascript"}]
     [:script {:src "/js/main.js"
               :type "text/javascript"}]
     [:script {:type "text/javascript"}
      (str "var trace_token = '" *anti-forgery-token* "';")
      "trace.colonnade.init_coll();"]]]))

(def ^:private prefix-num-re #"([A-Za-z_-]*)(\d+)")

(defn prefix-num-comparator
  "Normalize strings which consist of a prefix followed by an integer."
  [x]
  (or (and (string? x)
           (if-let [[_ px nx] (re-matches prefix-num-re x)]
             (str px (subs "000000000000" 0 (- 12 (count nx))) nx)))
      x))


(defn post-query [con db {:keys [columns query rules args drop-rows max-rows
                                 timeout log format keyset-column]}]
  (let [query (if (string? query)
                (edn/read-string query)
                query)
        rules (if (string? rules)
                (edn/read-string rules)
                rules)
        args  (if (string? args)
                (edn/read-string args)
                args)
        columns (if (string? columns)
                  (edn/read-string columns))
        args (if (seq rules)
               (cons rules args)
               args)
        args (if log
               (cons (d/log con) args)
               args)
        results (d/query
                 {:query query
                  :args (cons db args)
                  :timeout (or timeout 5000)})]
    (case format
      "csv"
      {:status 200
       :headers {"Content-Type" "text/csv"
                 "Content-Disposition" "attachment; filename=colonnade.csv"}
       :body (with-out-str
               (write-csv *out* results :quote? (constantly true)))}

      "keyset"
      (let [class (:pace/identifies-class
                   (entity
                    db
                    (:attribute (get columns keyset-column))))]
        {:status 200
         :headers {"Content-Type" "text/plain"
                   "Content-Disposition" "attachment; filename=colonnade.ace"}
         :body (with-out-str
                 (doseq [[o] results]
                   (println class ":" (str \" o \"))))})

      "ace"
      (binding [acedump/*timestamps* false
                acedump/*xrefs*      true]
        (let [clid (:attribute (get columns keyset-column))]
          {:status 200
           :headers {"Content-Type" "text/plain"
                     "Content-Disposition" "attachment; filename=colonnade.ace"}
           :body (with-out-str
                   (doseq [[id] results]
                     (acedump/dump-object (acedump/ace-object db [clid id]))))}))


      "acets"
      (binding [acedump/*timestamps* true
                acedump/*xrefs*      true]
        (let [clid (:attribute (get columns keyset-column))]
          {:status 200
           :headers {"Content-Type" "text/plain"
                     "Content-Disposition" "attachment; filename=colonnade.ace"}
           :body (with-out-str
                   (doseq [[id] results]
                     (acedump/dump-object (acedump/ace-object db [clid id]))))}))



      ;; default
      {:status 200
       :headers {"Content-Type" "text/plain"}
       :body (pr-str {:query query
                      :results (cond->> (sort-by-cached
                                         (comp prefix-num-comparator first) results)
                                 drop-rows (drop drop-rows)
                                 max-rows (take max-rows))
                      :drop-rows drop-rows
                      :max-rows max-rows
                      :count (count results)})})))



(defn colonnade [db]
  (routes
   (GET "/" req (page req))
   (POST "/query" {params :params con :con} (post-query con db params))))
