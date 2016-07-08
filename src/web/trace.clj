(ns web.trace
  (:use hiccup.core
        web.anti-forgery
        clojure.walk
        pseudoace.utils)
  (:require [datomic.api :as d :refer (db history q touch entity)]
            [clojure.string :as str]
            [compojure.core :refer (defroutes GET POST context wrap-routes)]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.util.response :refer [file-response]]
            [cemerick.friend :as friend]
            [environ.core :refer (env)]))

;;
;; Back-end for the TrACe tree-viewer/editor.  Current TrACe uses the "obj2" protocol.
;; Any references to the original "obj" protocol are vestigial.
;;

(declare touch-link)
(declare obj2)

(def 
  ^{:dynamic true
    :doc "If bound to a map of :class/id -> attributes, use those attributes
          as possible object labels."}
  *class-titles* nil)

(defn class-titles-for-user 
  "Return the default *class-titles* map for a user."
  [db username]
  (if username 
    (->> (q '[:find ?class-id ?attr-id
              :in $ ?username
              :where [?user :user/name ?username]
                     [?t :wormbase.title/user ?user]
                     [?t :wormbase.title/class ?class]
                     [?t :wormbase.title/attribute ?attr]
                     [?class :db/ident ?class-id]
                     [?attr :db/ident ?attr-id]]
            db username)
         (into {}))))

(defn- object-link 
  "Create a lookup-ref or labelled lookup-ref to object `v` of class `class`."
  [class v]
  (let [ref [class (class v)]]
    (or 
     (some->> (get *class-titles* class)
              (get v)
              (conj ref))
     ref)))
  

(defn touch-link-ref [ke v]
  (cond
   (:db/isComponent ke)  (touch-link v)
   (:pace/obj-ref ke)    (object-link (:pace/obj-ref ke) v)
   (:db/ident v)         (:db/ident v)
   :default              (if-let [class (first (filter #(= (name %) "id") (keys v)))]
                           (object-link class v)
                           v)))

(defn touch-link [ent]
  (let [db (d/entity-db ent)]
    (into {}
      (for [k     (keys ent)
            :let  [v (k ent)
                   ke (entity db k)]]
        [k
         (if (= (:db/valueType ke) :db.type/ref)
           (if (= (:db/cardinality ke) :db.cardinality/one)
             (touch-link-ref ke v)
             (into #{}
               (for [i v]
                 (touch-link-ref ke i))))
           v)]))))


(defn obj2-attr [db maxcount exclude datoms]
  (let [attr (entity db (:a (first datoms)))]
    (if (not (or (exclude (:db/ident attr))
                 (= :importer/temp (:db/ident attr))))
      {:key   (:db/ident attr)
       :group (if-let [tags (:pace/tags attr)]
                (first (str/split tags #" ")))
       :type  (:db/valueType attr)
       :class (:pace/obj-ref attr)
       :comp  (or (:db/isComponent attr) false)
       :count (count datoms)
       :values
       (if (or (not maxcount)
               (< (count datoms) maxcount))
         (for [d datoms]
           {:txn (:tx d)
            :id (if (:db/isComponent attr)
                  (str (:v d)))
            :val (cond
                   (:db/isComponent attr)
                   (obj2 db (:v d) maxcount)
                   (= (:db/valueType attr) :db.type/ref)
                   (touch-link-ref attr (entity db (:v d)))
                   :default
                   (:v d))}))})))
         

(defn obj2
  ([db ent maxcount] (obj2 db ent maxcount #{}))
  ([db ent maxcount exclude]
   (->> (d/datoms db :eavt ent)
        (seq)
        (sort-by :a)
        (partition-by :a)
        (map (partial obj2-attr db maxcount exclude))
        (filter identity))))

(defn- xref-component-parent [db ent obj-ref]
  (let [[e a v t]   (first (d/datoms db :vaet ent))
        attrent     (entity db a)
        attr        (:db/ident attrent)
        attr-ns    (namespace attr)
        attr-name  (name attr)
        revattr    (keyword attr-ns
                            (str "_" attr-name))
        ent         (entity db e)]
    (cond
      (obj-ref ent)
      {:key     attr
       :type    :db.type/ref
       :comp    false
       :count   1
       :values [{:txn t
                 :val (object-link obj-ref ent)}]}

      ent
      (xref-component-parent db e obj-ref)

      ;; if no parent, we'll return nil
      )))
    

(defn xref-obj2-attr [db ent xref maxcount]
  (let [attr       (:pace.xref/attribute xref)
        obj-ref    (:pace.xref/obj-ref xref)
        attr-ns    (namespace attr)
        attr-name  (name attr)
        comp?      (not= attr-ns (namespace obj-ref))
        revattr    (keyword attr-ns
                            (str "_" attr-name))
        val-datoms (seq (d/datoms db :vaet ent attr))]
    (when (and val-datoms
               (not (.startsWith attr-ns "2")))
      {:key      revattr
       :group    "XREFs"
       :type     :db.type/ref
       :comp     comp?
       :count    (count val-datoms)
       :values
       (if (or (not maxcount)
               (< (count val-datoms) maxcount))
         (for [[val _ _ txn] val-datoms]
           {:txn txn
            :val (if comp?
                   (conj-if (obj2 db val maxcount #{attr})
                            (xref-component-parent db val obj-ref))
                   (object-link obj-ref (entity db val)))
           }))})))

(defn xref-obj2
  "Make obj2-format records of all inbound attributes to `ent`."
  [db clid ent maxcount]
  (for [xref (:pace/xref (entity db clid))
        :let [vm         (xref-obj2-attr db ent xref maxcount)]
        :when vm]
    vm))

(defn find-txids [props]
  (mapcat
   (fn [{:keys [key values comp]}]
     (mapcat 
      (fn [v]
        (let [txn [(:txn v)]]
          (if comp
            (concat txn (find-txids (:val v)))
            txn)))
      values))
   props))

(defn get-raw-txns [ddb txids]
  (for [t txids
        :let [te (as-> (entity ddb t) $
                       (touch $)
                       (into {} $)
                       (assoc $ :db/id t))]]
    (if-let [curator (:wormbase/curator te)]
      (assoc te :wormbase/curator
             {:person/id            (:person/id curator)
              :person/standard-name (:person/standard-name curator)})
      te)))

(defn get-raw-obj2 [ddb class id max-out max-in txns?]
 (binding [*class-titles* (class-titles-for-user ddb (:username (friend/current-authentication)))]
  (let [clid  (keyword class "id")
        entid (->> [clid id]
                   (entity ddb)
                   (:db/id))]
    (if entid
      (let [props (concat (obj2 ddb entid max-out)
                          (xref-obj2 ddb clid entid max-in))
            txids (set (find-txids props))]
        {:status 200
         :headers {"Content-Type" "text/plain"}
         :body (pr-str {:props  props
                        :id     (str entid)
                        :txns   (if txns?
                                  (get-raw-txns ddb txids))})})
      {:status 404
       :body "Not found"}))))

(defn get-raw-attr2-out [ddb entid attr txns?]
  (let [prop (obj2-attr ddb nil (seq (d/datoms ddb :eavt entid attr)))
        txids (set (find-txids [prop]))]
   {:status 200
    :headers {"Content-Type" "text/plain"}
    :body (pr-str (assoc
                    prop
                    :txns (if txns? (get-raw-txns ddb txids))))}))

(defn get-raw-attr2-in [ddb entid attr txns?]
  (let [xref (entity ddb (q '[:find ?x .
                              :in $ ?a
                              :where [?x :pace.xref/attribute ?a]]
                            ddb attr))
        prop (xref-obj2-attr ddb entid attr nil)
        txids (set (find-txids [prop]))]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (pr-str (assoc
                     prop
                     :txns (if txns? (get-raw-txns ddb txids))))}))
  

(defn get-raw-attr2 [ddb entid attr-name txns?]
 (binding [*class-titles* (class-titles-for-user ddb (:username (friend/current-authentication)))]
  (let [attr (keyword (.substring attr-name 1))]
    (if (.startsWith (name attr) "_")
      (get-raw-attr2-in ddb entid (keyword (namespace attr)
                                            (.substring (name attr) 1))
                        txns?)
      (get-raw-attr2-out ddb entid attr txns?)))))

(defn get-raw-history2 [db entid attr]
  (let [hdb    (d/history db)
        schema (entity db attr)
        valmap (cond
                 (:pace/obj-ref schema)
                 (comp (:pace/obj-ref schema) (partial entity db))

                 (:db/isComponent schema)
                 identity

                 (= (:db/valueType schema) :db.type/ref)
                 (fn [eid]
                   (let [e (entity db eid)]
                     (if-let [ident (:db/ident e)]
                       (name ident)
                       eid)))
                 
                 :default
                 identity)
        datoms (sort-by :tx (seq (d/datoms hdb :eavt entid attr)))]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (pr-str {:datoms (map (fn [[e a v tx a]] 
                                   {:e e
                                    :a a
                                    :v (valmap v)
                                    :txid tx
                                    :added? a})
                                 datoms)
                    :endid entid
                    :attr attr
                    :txns (get-raw-txns db (set (map :tx datoms)))})}))

(defn get-transaction-notes [db id]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (q '[:find ?d .
              :in $ ?tx
              :where [?tx :db/doc ?d]]
            db id)})
            

(defn get-raw-ent [ddb id]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body (pr-str (touch (entity ddb id)))})

(def cljs-symbol (re-pattern "^[:]?([^0-9/]*/)?([^0-9/][^/]*)$"))

(defn get-schema-classes [db]
  (->> (q '[:find ?cid
            :where [?cid :pace/identifies-class _]]
          db)
       (map (fn [[cid]]
              (let [ent (into {} (touch (entity db cid)))]
                (assoc ent :pace/xref
                  (for [x (:pace/xref ent)
                        :let [x (touch x)]     
                        :when true #_(re-matches cljs-symbol (str (:pace.xref/attribute x)))]
                    x)))))))

(defn get-schema-attributes [db]
  (->> (q '[:find ?attr
            :where [?attr :pace/tags _]]
          db)
       (map (fn [[attr]]
              (touch (entity db attr))))))

(defn get-schema [ddb]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (pr-str {:classes    (get-schema-classes ddb)
                  :attributes (get-schema-attributes ddb)})})

(defn viewer-page [{:keys [db] :as req}]
  (html
   [:html
    [:head
     [:link {:rel "stylesheet"
             :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.0/css/bootstrap.min.css"}]
     [:link {:rel "stylesheet"
             :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.0/css/bootstrap-theme.min.css"}]
     [:link {:rel "stylesheet"
             :href "//maxcdn.bootstrapcdn.com/font-awesome/4.2.0/css/font-awesome.min.css"}]
     [:link {:rel "stylesheet"
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
        [:h1#page-title "TrACeView"]
        [:div#header-content]]]
      [:div.container-fluid
       [:div#tree]]

      [:script {:src "/js/out/goog/base.js"
                :type "text/javascript"}]
      [:script {:src "/js/main.js"
                :type "text/javascript"}]
      [:script {:type "text/javascript"}
       (str "/* " (friend/current-authentication req) " */")
       (if-let [id (friend/identity req)]
         (if (:wbperson (friend/current-authentication req))
           (str "trace_logged_in = '" (:current id) "';")
           "trace_logged_in = null;")
         "trace_logged_in = null;")
       (str "trace_token = '" *anti-forgery-token* "';")
       "trace.core.init_trace();"]]]]))
  

(defn- id-report [db datoms]
  (for [d datoms
        :let [i (:db/ident (entity db (.a d)))]
        :when (= (name i) "id")]
    (.v d)))

(defn transact [{:keys [edn-params con] :as req}]
  (try
    (let [txr
          @(d/transact
            con
            (conj (postwalk
                   (fn [x]
                     (if (and (coll? x)
                              (= (count x) 2)
                              (= (first x) :db/id))
                       (let [v (second x)]
                         (if (string? v)
                           (Long/parseLong v)
                           x))
                       x))
                   (:tx edn-params))
                  {:db/id (d/tempid :db.part/tx)
                   :wormbase/curator [:person/id (:wbperson (friend/current-authentication req))]}))]
      {:status 200
       :body (pr-str {:status "OK"
                      :ids (id-report (:db-after txr) (:tx-data txr))})})
    (catch Exception e {:status 500
                        :body (.getMessage e)})))

(defn get-prefix-search [db class prefix]
  (let [names (->> (d/seek-datoms db :avet (keyword class "id") prefix)
                   (map :v)
                   (take-while (fn [^String s]
                                 (.startsWith s prefix))))]
  {:status 200
   :headers {"Content-Type" "text/plain"}  ; for now
   :body (pr-str 
          {:count (count names)
           :names (take 10 names)})}))

(defn get-raw-txns2 [db ids]
  {:status 200
   :header {"Content-Type" "text/plain"}
   :body (pr-str
          {:txns (get-raw-txns db ids)})})

(defn in-transaction [log tx]
  (->> (d/tx-range log tx (inc tx))
       (mapcat :data)
       (map :e)
       (set)))
