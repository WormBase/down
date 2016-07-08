(ns web.curate.gene
  (:use hiccup.core
        pseudoace.utils
        web.curate.common)
  (:import java.util.Date)
  (:require [datomic.api :as d :refer (q db history touch entity)]
            [clojure.string :as str]
            [cemerick.friend :as friend :refer [authorized?]]
            [web.anti-forgery :refer [anti-forgery-field]]))

(defmethod lookup "Gene"
  [_ db id]
  (q '[:find [?gid ...]
       :in $ ?name
       :where (or-join [?g ?name]
               [?g :gene/id ?name]
               [?g :gene/sequence-name ?name]
               [?g :gene/molecular-name ?name]
               [?g :gene/public-name ?name]
               (and
                [?cgc :gene.cgc-name/text ?name]
                [?g :gene/cgc-name ?cgc])
               (and
                [?alt :gene.other-name/text ?name]
                [?g :gene/other-name ?alt]))
              [?g :gene/id ?gid]]
     db (or id "")))
     
(def species-longnames
  {"elegans"        "Caenorhabditis elegans"
   "briggsae"       "Caenorhabditis briggsae"
   "remanei"        "Caenorhabditis remanei"
   "brenneri"       "Caenorhabditis brenneri"
   "japonica"       "Caenorhabditis japonica"
   "pristionchus"   "Pristionchus pacificus"
   "brugia"         "Brugia malayi"
   "ovolvulus"      "Onchocerca volvulus"})

(defn species-menu
  "Build hiccup for a species menu"
  ([name] (species-menu nil))
  ([name sel]
    (let [sel (or sel "elegans")]
     [:select {:name name}
      (for [s (keys (sort-by val species-longnames))]
        [:option {:value s
                  :selected (if (= sel s)
                              "yes")}
         (species-longnames s)])])))
                      
(def name-checks
  {"elegans"    {"CGC"         #"^[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"	
	         "Sequence"    #"(^([A-Z0-9_cel]+)\.\d+$)|(^([A-Z0-9_cel]+)\.t\d+$)"
	         "Public_name" #"^[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^([A-Z0-9_]+)\.\d+$"}

   "briggsae"   {"CGC"         #"(^Cbr-[a-z21]{3,4}-[1-9]\d*(\.\d+)?)|(^Cbr-[a-z21]{3,4}\([a-z]+\d+\)?$)"
		 "Sequence"    #"^CBG\d{5}$"
		 "Public_name" #"(^Cbr-[a-z21]{3,4}-[1-9]\d*(\.\d+)?)|(^Cbr-[a-z21]{3,4}\([a-z]+\d+\)?$)|^CBG\d{5}$'"}

   "remanei"    {"CGC"         #"^Cre-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^CRE\d{5}$"
		 "Public_name" #"^Cre-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^CRE\d{5}$"}

   "brenneri"   {"CGC"         #"^Cbn-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^CBN\d{5}$"
		 "Public_name" #"^Cbn-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^CBN\d{5}$"}

   "pristionchus" {"CGC"         #"^Ppa-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		   "Sequence"    #"^PPA\d{5}$"
		   "Public_name" #"^Ppa-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^PPA\d{5}$"}

   "japonica"   {"CGC"         #"^Cjp-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^CJA\d{5}$"
		 "Public_name" #"^Cjp-[a-z]{3,4}-[1-9]\d*(\.\d+)?$|^CJA\d{5}$"}

   "brugia"     {"CGC"         #"^Bma-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
		 "Sequence"    #"^Bm\d+$"
		 "Public_name" #"^Bma-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$|^Bm\d+$"}
                 
   "ovolvulus"  {"CGC"         #"^Ovo-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$"
                 "Sequence"    #"OVOC\d+$"
                 "Public_name" #"^Ovo-[a-z21]{3,4}-[1-9]\d*(\.\d+)?$|^OVOC\d+$"}})

(defn validate-name
  [name type species]
  (if-let [expr (get-in name-checks [species type])]
    (if-not (re-matches expr name)
      (str "Name '" name "' does not validate for " species ":" type))
    (str "Not allowed: " species ":" type)))

(defn lookup-name
  [db type name]
  (if-let [query (case type
                   "CGC"
                   '[:find [?g ...]
                     :in $ ?name
                     :where [?cgc :gene.cgc-name/text ?name]
                            [?g :gene/cgc-name ?cgc]]
                   
                   "Sequence"
                   '[:find [?g ...]
                     :in $ ?name
                     :where [?g :gene/sequence-name ?name]])]
    (->>
     (q query db name)
     (map (partial entity db))
     (seq))))

;;
;; Public name manglement
;;

(defn update-public-name
  "Check if the public name of Gene `p` should be updated after applying
   transaction `tx` to `db`, and return a list of transaction data if needed." 
  [db tx p]
  (let [{:keys [db-after tempids]} (d/with db tx)
        rp (or (d/resolve-tempid db-after tempids p) p)    ;; returns null if p isn't a tempid
        names (->> (q '[:find ?type ?name
                        :in $ ?gene
                        :where (or-join [?gene ?type ?name]
                                 (and
                                   [?gene :gene/public-name ?name]
                                   [(ground "Public_name") ?type])
                                 (and
                                   [?gene :gene/sequence-name ?name]
                                   [(ground "Sequence") ?type])
                                 (and
                                   [?gene :gene/cgc-name ?cgc]
                                   [?cgc :gene.cgc-name/text ?name]
                                   [(ground "CGC") ?type]))]
                      db-after rp)
                   (into {}))
        old-public (names "Public_name")
        new-public (some names ["CGC" "Sequence" "Public_name"])]
    (if (and new-public
             (not= new-public old-public))
      [[:db/add p :gene/public-name new-public]])))   ;; go back to using the tempid...

;;
;; Query gene
;;

(defn query-gene [req]
  "hello")

;;
;; New gene
;;

(defn do-new-gene [con remark species new-name type]
 (let [db (db con)]
  (if-let [err (or
                (validate-name new-name type species)
                (if-let [old (lookup-name db type new-name)]
                  (format "%s name '%s' already used for gene '%s'."
                          type new-name (:gene/id (first old)))))]
    {:err [err]}
    (let [tid (d/tempid :db.part/user)
          tx  [[:wb/mint-identifier :gene/id [tid]]
               (vmap
                :db/id tid
                :gene/sequence-name (if (= type "Sequence")
                                      new-name)
                :gene/cgc-name      (if (= type "CGC")
                                      {:gene.cgc-name/text new-name})
                :gene/version       1
                :gene/version-change {
                    :gene.version-change/version 1
                    :gene.version-change/person  [:person/id (:wbperson (friend/current-authentication))]
                    :gene.version-change/date    (Date.)   ;; Now.  May be a few seconds different from
                                                           ;; the :db/txInstant :-(.                  
                    :gene-history-action/created true}
                :gene/status {
                    :gene.status/status :gene.status.status/live}
                :locatable/method [:method/id "Gene"]
                :gene/species [:species/id (species-longnames species)])
               (txn-meta)]
          tx (concat tx (update-public-name db tx tid))]
      (try
        (let [txr @(d/transact con (concat
                                    tx
                                    #_(update-public-name (db con) tx tid)))
              db  (:db-after txr)
              ent (touch (entity db (d/resolve-tempid db (:tempids txr) tid)))
              who (:wbperson (friend/current-authentication))
              gc  (if (= type "CGC")
                    (or (second (re-matches #"(\w{3,4})(?:[(]|-\d+)" new-name))
                        "-"))]
          {:done (:gene/id ent)})
        (catch Exception e {:err [(.getMessage e)]}))))))

(defn new-gene [{db :db con :con 
                 {:keys [remark species new_name type]} :params}]
  (let [type (if (= type "CGC")
               (if true #_(authorized? #{:user.role/cgc} friend/*identity*)
                 "CGC" "Sequence")
               type)
        result (if new_name
                 (do-new-gene con remark species new_name type))]
        
    (page db
     (if (:done result)
       [:div.block
        [:h3 "ID generated"]
        "New gene " (link "Gene" (:done result))
        " with name " [:strong new_name] " created."]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Request new Gene ID"]
         (for [err (:err result)]
           [:p.err err])
         
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Name type"]
           [:td
            [:select {:name "type"}
             (if true #_(authorized? #{:user.role/cgc} friend/*identity*)
               [:option {:selected (if (= type "CGC") "yes")} "CGC"])
             [:option {:selected (if (= type "Sequence") "yes")} "Sequence"]]]]
          [:tr
           [:th "Name of gene"]
           [:td
            [:input {:type "text"
                     :name "new_name"
                     :size 20
                     :maxlength 40
                     :value (or new_name "")}]]]
          [:tr
           [:th "Species"]
           [:td
            (species-menu "species" species)]]
          [:tr
           [:th "Additional comment (e.g. instructions to nomenclature person)"]
           [:td
            [:input {:type "text"
                     :name "remark"
                     :size 40
                     :maxlength 200
                     :value (or remark "")}]]]]
         [:input {:type "submit"}]]]))))


;;
;; Kill gene
;;

(defn do-kill-gene [con id reason]
  (let
      [db      (db con)
       cid     (first (lookup "Gene" db id))
       gene    (and cid (entity db [:gene/id cid]))
       errs    (->> [(if-not cid
                       (str id " does not exist"))

                     (if (= (:gene.status/status (:gene/status gene))
                            :gene.status.status/dead)
                       "What is dead may never die.")

                     (if (or (not reason) (empty? reason))
                       "Must give a reason for killing a gene.")]
                    (filter identity)
                    (seq))]
    (if errs
      {:err errs}
      (let [version (or (:gene/version gene) 1)
            txn [;; CAS-ing the version should catch any race conditions.
                 [:db.fn/cas [:gene/id cid] :gene/version version (inc version)]
                 
                 (vmap
                  :db/id [:gene/id cid]
                  :gene/status {
                      :gene.status/status :gene.status.status/dead
                  }
                  
                  :gene/version-change {
                    :gene.version-change/version (inc version)
                    :gene.version-change/person  [:person/id (:wbperson (friend/current-authentication))]
                    :gene.version-change/date    (Date.)   ;; Now.  May be a few seconds different from
                                                           ;; the :db/txInstant :-(.
                    :gene-history-action/killed true
                  }

                  :gene/remark
                  (if (and reason (not (empty? reason)))
                    {
                     :gene.remark/text reason
                     })
                  )
                 
                 (txn-meta)]]
        (try
          (let [txr @(d/transact con txn)]
            {:done true
             :canonical cid})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))

(defn kill-object [domain
                   {con :con
                    {:keys [id reason]} :params}]
  (page (db con)
   (let [result (if id
                  (do-kill-gene con id reason))]
     (if (:done result)
       [:div.block
        [:h3 "Kill " (lc domain)]
        [:p (link domain (:canonical result)) " has been killed."]]
       [:div.block
        [:form {:method "POST"}
         (anti-forgery-field)
         [:h3 "Kill " (lc domain)]
         (for [err (:err result)]
           [:p.err err])
         [:table.info
          [:tr
           [:th "Enter ID to kill"]
           [:td (ac-field "id" domain id)]]
          [:tr
           [:th "Reason for removal"]
           [:td
            [:input {:type "text"
                     :name "reason"
                     :size 40
                     :maxlength 200
                     :value (or reason "")}]]]]
         [:input {:type "submit"}]]]))))

(defn- do-add-name [con id type name species papers people]
  (let
    [db       (db con)
     cid      (first (lookup "Gene" db id))
     gene     (and cid (entity db [:gene/id cid]))
     old-name (if cid
                (q (case type
                     "Sequence"
                     '[:find ?name .
                       :in $ ?gid
                       :where [?gene :gene/id ?gid]
                              [?gene :gene/sequence-name ?name]]

                     "CGC"
                     '[:find ?name .
                       :in $ ?gid
                       :where [?gene :gene/id ?gid]
                              [?gene :gene/cgc-name ?cgc]
                              [?cgc :gene.cgc-name/text ?name]])
                   db cid))
     errs (those
           (if-not cid
             (str "Couldn't find " id))
           #_(if (= type "CGC")
               (if-not (authorized? #{:user.role/cgc} friend/*identity*)
                 "You do not have permission to add CGC names."))
           (if-not (name-checks species)
             (str "Unknown species " species))
           (if-not (#{"CGC" "Sequence" "Public_name"} type)
             (str "Unknown type " type))
           (if-let [existing (first (lookup "Gene" db name))]
             (list name " already exists as " (link "Gene" existing)))
           (if-let [bad-people (seq (filter #(not (entity db [:person/id %])) people))]
             (str "Invalid person id: " (str/join ", " bad-people)))
           (if-let [bad-papers (seq (filter #(not (entity db [:paper/id %])) papers))]
             (str "Invalid paper id: " (str/join ", " bad-papers)))
           (validate-name name type species))]
    (if errs
      {:err errs}
      (let [version (or (:gene/version gene) 1)
            txn [[:db.fn/cas [:gene/id cid] :gene/version version (inc version)]


                 (vmap
                  :db/id [:gene/id cid]

                  :gene/sequence-name (if (= type "Sequence")
                                        name)

                  :gene/cgc-name (if (= type "CGC")
                                   (vmap
                                    :gene.cgc-name/text
                                    name

                                    :evidence/paper-evidence
                                    (seq
                                     (for [p papers]
                                       [:paper/id p]))
                                    
                                    :evidence/person-evidence
                                    (seq
                                     (for [p people]
                                       [:person/id p]))))

                  :gene/version-change
                  (vmap
                   :gene.version-change/version (inc version)
                   :gene.version-change/person  [:person/id (:wbperson (friend/current-authentication))]
                   :gene.version-change/date    (Date.)   ;; Now.  May be a few seconds different from
                                                          ;; the :db/txInstant :-(.
                   :gene-history-action/sequence-name-change (if (= type "Sequence")
                                                               name)
                   :gene-history-action/cgc-name-change (if (= type "CGC")
                                                          name)))
            
                 (txn-meta)]]
        (try
          (let [txr @(d/transact con (concat
                                      txn
                                      (update-public-name db txn [:gene/id cid])))]
            {:done true
             :canonical cid})
          (catch Exception e {:err [(.getMessage e)]}))))))

(defn- multi-input-vals [vals]
  (cond
    (string? vals)
    (if (empty? vals) nil [vals])

    :default
    (filterv seq vals)))

(defn add-gene-name [{db :db
                      con :con
                      {:keys [id type name species paper person]} :params}]
  (page db
   (let [paper  (multi-input-vals paper)
         person (multi-input-vals person)
         result (if (and id name)
                  (do-add-name con id type name species paper person))]
     (if (:done result)
       [:div.block
        "Added " [:strong name] " as " type " name for " (link "Gene" (:canonical result))]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Add gene name"]
         (for [err (:err result)]
           [:p.err err])
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "ID:"]
           [:td (ac-field "id" "Gene" id)]]
          [:tr
           [:th "Type:"]
           [:td
            [:select {:name "type"}
             (if #_(authorized? #{:user.role/cgc} friend/*identity*) true
               [:option {:selected (if (= type "CGC") "yes")} "CGC"])
             [:option {:selected (if (= type "Sequence") "yes")} "Sequence"]]]]
          [:tr
           [:th "Name to add:"]
           [:td [:input {:type "text"
                         :name "name"
                         :size 20
                         :maxlength 40
                         :value (or name "")}]]]
          [:tr
           [:th "Species:"]
           [:td
            (species-menu "species" species)]]

          [:tr
           [:th "Person:"]
           [:td.multi-input
            (for [p (conj person "")]
              [:div
               [:input {:type "text"
                        :name "person"
                        :size 20
                        :maxlength 40
                        :value p}]])]]

          [:tr
           [:th "Paper:"]
           [:td.multi-input
            (for [p (conj paper "")]
              [:div
               [:input {:type "text"
                        :name "paper"
                        :size 20
                        :maxlength 40
                        :value p}]])]]]
         
         [:input {:type "submit"}]]]))))

;;
;; Merge two genes
;;

(defn cgc-name [db id]
  (q '[:find ?name .
       :in $ ?id
       :where [?g :gene/id ?id]
              [?g :gene/cgc-name ?cgc]
              [?cgc :gene.cgc-name/text ?name]]
     db id))
     
(defn do-merge-genes [con id idx]
  (let [db   (db con)
        cid  (first (lookup "Gene" db id))
        cidx (first (lookup "Gene" db idx))
        obj  (if cid
               (entity db [:gene/id cid]))
        objx (if cidx
               (entity db [:gene/id cidx]))
        is-cgc? true #_(authorized? #{:user.role/cgc} friend/*identity*)
        errs (->> [(if-not cid
                     (str "No match for " id))
                   (if-not cidx
                     (str "No match for " idx))
                   (if (= (:object/live obj) false)
                     (str "Cannot merge because " cid " is not live."))
                   (if (and cid (= cid cidx))
                     (str "Cannot merge " (if (= id cid)
                                            cid
                                            (str id " (" cid ")"))
                          " into itself"))
                   (if-not is-cgc?
                     (if-let [cgcx (cgc-name db cidx)]
                       (if-let [cgc (cgc-name db cid)]
                         (str "Both genes have cgc names " cgc " and " cgcx ". "
                              "please contact the Geneace curator.")
                         (str "Gene to be killed has a cgc name " cgcx "."
                              "please contact the Geneace curator."))))]
                   (filter identity)
                   (seq))
        warnings (those
                  (if-let [cgcx (cgc-name db cidx)]
                    (format "Killed gene %s had CGC name %s." cidx cgcx))
                  (if (= (:gene.status/status (:gene/status objx)) :gene.status.status/dead)
                    (format "Merged gene %s was dead." cidx)))]
    (if errs
      {:err errs}
      (let [v   (or (:gene/version obj) 1)
            vx  (or (:gene/version objx) 1)
            txn [[:db.fn/cas [:gene/id cid] :gene/version v (inc v)]
                 [:db.fn/cas [:gene/id cidx] :gene/version vx (inc vx)]
                 
                 {:db/id [:gene/id cid]
                  :gene/acquires-merge [[:gene/id cidx]]
                  :gene/version-change {
                     :gene.version-change/version (inc v)
                     :gene.version-change/person  [:person/id (:wbperson (friend/current-authentication))]
                     :gene.version-change/date    (Date.)   ;; Now.  May be a few seconds different from
                                                            ;; the :db/txInstant :-(.
                     :gene-history-action/acquires-merge [:gene/id cidx]
                                        }}
                 
                 {:db/id [:gene/id cidx]
                  :gene/status {
                      :gene.status/status :gene.status.status/dead
                  }
                  
                  :gene/version-change {
                    :gene.version-change/version (inc vx)
                    :gene.version-change/person  [:person/id (:wbperson (friend/current-authentication))]
                    :gene.version-change/date    (Date.)   ;; Now.  May be a few seconds different from
                                                           ;; the :db/txInstant :-(.
                    :gene-history-action/merged-into [:gene/id cid]
                                        }}
                 
                  (txn-meta)]]
        (try
          (let [txr @(d/transact con txn)]
            #_(ns-email (format "Merged genes %s (%s) - %s (%s)" id cid idx cidx)
                "LIVE" (format "retained gene %s" cid)
                "DEAD" (format "killed   gene %s" cidx)
                "WARNING" (if warnings (str/join "; " warnings)))
            {:done true
             :warnings warnings
             :cid cid :cidx cidx})
          (catch Exception e {:err [(.getMessage (.getCause e))]}))))))

(defn merge-gene [{con :con
                   db  :db
                   {:keys [id idx]} :params}]
  (page db
   (let [result (if (and id idx)
                  (do-merge-genes con id idx))]
     (if (:done result)
       [:div.block
        [:h3 "Merged Genes"]
        [:p "Gene " (link "Gene" (:cidx result)) " is DEAD and has been merged into " (link "Gene" (:cid result))]
        (for [w (:warnings result)]
          [:p.err "WARNING: " w])]
       [:div.block
        [:form {:method "POST"}
         [:h3 "Merge genes"]
         (for [err (:err result)]
           [:p.err err])
         (anti-forgery-field)
         [:table.info
          [:tr
           [:th "Gene to stay alive:"]
           [:td (ac-field "id" "Gene" id)]]
          [:tr
           [:th "Gene to remove:"]
           [:td (ac-field "idx" "Gene" idx)]]]
         [:input {:type "submit"}]]]))))
