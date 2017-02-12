(ns down.colonnade
  (:require
   [clojure.data.csv :refer [write-csv]]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [compojure.core :refer [defroutes GET POST routes]]
   [compojure.handler :as handler]
   [compojure.route :as route]
   [datomic.api :as d]
   [down.common :refer [head identity-header]]
   [down.db :refer [ws-version]]
   [down.util :refer [format-datetime]]
   [hiccup.core :refer [html]]
   [pseudoace.acedump :as acedump]
   [pseudoace.utils :refer [sort-by-cached]]
   [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
   [ring.middleware.format :refer [wrap-restful-format]]
   [ring.util.http-response :as resp]))

(defn- page [{:keys [db] :as req}]
  (html
   [:html
    head
    [:body#colonnade
     [:div.root
      [:div.header
       (identity-header db)
       [:div.header-main
        [:h1#page-title "Colonnade"]
        [:img.banner
         {:src "/img/kazannevsky.jpg"
          :width 130
          :style "float: right"}]]
       [:div#header-content]]
      [:div.container-fluid
       [:div#table-maker]]]
     [:script {:src "/compiled/js/site.min.js"
               :type "text/javascript"}]
     [:script {:type "text/javascript"}
      (str "var csrf_token = '" *anti-forgery-token* "';\n")
      "thomas.colonnade.init_coll();"]]]))

(def ^:private prefix-num-re #"([A-Za-z_-]*)(\d+)")

(defn prefix-num-comparator
  "Normalize strings which consist of a prefix followed by an integer."
  [x]
  (or (and (string? x)
           (if-let [[_ px nx] (re-matches prefix-num-re x)]
             (str px (subs "000000000000" 0 (- 12 (count nx))) nx)))
      x))

(def content-types-by-query-format
  {"json" "application/json"
   "edn" "application/edn"
   "csv" "text/csv"
   nil "application/edn"})

(defn- query-response
  [opts]
  (let [body (:body opts)
        status (:status opts)
        content-type (get content-types-by-query-format
                          (:format opts)
                          "text/plain")]
    (some-> body
            (resp/ok)
            (resp/status status)
            (resp/content-type content-type))))


(defn- filename-for-download [opts]
  (let [content-type (or (:content-type opts)
                         (-> opts
                             :format
                             (get content-types-by-query-format
                                  "text/plain")))
        file-ext (last (str/split content-type #"/"))
        ts-now (format-datetime)]
    (str "results-" ts-now "." file-ext)))

(defn download-as-file [opts]
  (let [filename (filename-for-download opts)
        response (query-response opts)]
    (assoc-in response
              [:headers "Content-Disposition"]
              (str "attachment; filename=" filename))))

(defn- download-ace-as-file
  [db columns keyset-column results & {:keys [include-timestamps]}]
  (binding [acedump/*timestamps* include-timestamps
            acedump/*xrefs* true]
    (let [clid (:attribute (get columns keyset-column))]
      (download-as-file
       {:format "ace"
        :body (with-out-str
                (doseq [[id] results]
                  (acedump/dump-object
                   (acedump/ace-object db [clid id]))))}))))

(defn- read-as-edn
  [s]
  (if (string? s)
    (binding [*read-eval* false]
      (edn/read-string s))
    s))

(defn post-query
  [con db {:keys [columns query rules args drop-rows max-rows
                  timeout log format keyset-column]
           :or {timeout 5000}}]
  (let [query (read-as-edn query)
        rules (read-as-edn rules)
        args  (read-as-edn args)
        columns (read-as-edn columns)
        args (if (seq rules)
               (cons rules args)
               args)
        args (if log
               (cons (d/log con) args)
               args)
        results (d/query {:query query
                          :args (cons db args)
                          :timeout timeout})
        download-ace (partial download-ace-as-file
                              db columns keyset-column results)]
    (case format
      "csv"
      (download-as-file
       {:format format
        :body (with-out-str
                (write-csv *out* results :quote? (constantly true)))})
      "json"
      (download-as-file
       {:format format
        :body (json/generate-string {:results results})})
      "keyset"
      (let [class (:pace/identifies-class
                   (d/entity
                    db
                    (:attribute (get columns keyset-column))))]
        (download-as-file
         {:format format
          :body (with-out-str
                  (doseq [[o] results]
                    (println class ":" (str \" o \"))))}))
      "ace"
      (download-ace :include-timestamps false)
      "acets"
      (download-ace :include-timestamps true)
      ;; EDN by default (as fall-through clause)
      (query-response
       {:format "edn"
        :body (pr-str
               {:query query
                :results (cond->> (sort-by-cached
                                   (comp prefix-num-comparator first)
                                   results)
                           drop-rows (drop drop-rows)
                           max-rows (take max-rows))
                :drop-rows drop-rows
                :max-rows max-rows
                :count (count results)})}))))

(defn colonnade [db]
  (routes
   (GET "/" req
     (page req))
   (POST "/query" {params :params con :con}
     (let [empty-query? (some->> params :query last keyword?)]
       (if empty-query?
         (resp/bad-request
          (format "Please specify a search criteria"))
         (post-query con db params))))))


