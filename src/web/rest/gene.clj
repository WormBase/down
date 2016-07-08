(ns web.rest.gene
  (:use cheshire.core
        web.rest.object
        pseudoace.binning)
  (:require [datomic.api :as d :refer (db q touch entity)]
            [clojure.string :as str]
            [pseudoace.utils :refer [vmap vmap-if vassoc cond-let those conjv]]
            [pseudoace.locatables :refer (root-segment)]))

;; Currently gene-specific, make more general in the future?

(defmacro def-rest-widget
  "Define a handler for a rest widget endpoint.  `body` is executed with `gene-binding` 
   will bound to the gene's entity-map, and should return a map of field values."
  [name [gene-binding] & body]
  `(defn ~name [db# id#]
     (if-let [~gene-binding (entity db# [:gene/id id#])]
       {:status 200
        :content-type "text/plain"
        :body (generate-string
               {:class "gene"
                :name id#
                :fields (do ~@body)}
               {:pretty true})}
       {:status 404
        :content-type "text/plain"
        :body (format "Can't find gene %s" id#)})))

;;
;; "name" field, included on all widgets.
;;

(defn- name-field [gene]
  {:data (pack-obj "gene" gene)
   :description (format "The name and WormBase internal ID of %s" (:gene/id gene))})

;;
;; Overview widget
;;

(def ^:private transcript-types
  {:transcript/asrna             "asRNA"
   :transcript/lincrna           "lincRNA"
   :transcript/processed-mrna    "mRNA"
   :transcript/unprocessed-mrna  "mRNA"
   :transcript/mirna             "miRNA"
   :transcript/ncrna             "ncRNA"
   :transcript/pirna             "piRNA"
   :transcript/rrna              "rRNA"
   :transcript/scrna             "scRNA"
   :transcript/snorna            "snoRNA"
   :transcript/snrna             "snRNA"
   :transcript/snlRNA            "snlRNA"
   :transcript/stRNA             "stRNA"
   :transcript/tRNA              "tRNA"})

(defn- transcript-type [transcript]
  (some transcript-types (keys transcript)))

(defn- gene-classification [gene]
  {:data
   (let [db   (d/entity-db gene)
         cds  (:gene/corresponding-cds gene)
         data {:defined_by_mutation (not (empty? (:variation.gene/_gene gene)))
               :type (cond
                      ;; This is pretty-much the reverse order of the Perl code
                      ;; because we never over-write anything
                      (q '[:find ?trans .
                           :in $ ?gene
                           :where [?gene :gene/version-change ?hist]
                                  [?hist :gene-history-action/transposon-in-origin ?trans]]
                         db (:db/id gene))
                      "Transposon in origin"
                         
                      (:gene/corresponding-pseudogene gene)
                      "pseudogene"

                      cds
                      "protein coding"

                      :default
                      (some #(transcript-type (:gene.corresponding-transcript/transcript %))
                            (:gene/corresponding-transcript gene)))
               :associated_sequence (not (empty? cds))
               :confirmed (if (q '[:find ?conf-gene .
                                   :in $ ?conf-gene
                                   :where [?conf-gene :gene/corresponding-cds ?gc]
                                          [?gc :gene.corresponding-cds/cds ?cds]
                                          [?cds :cds/prediction-status :cds.prediction-status/confirmed]]
                                 db (:db/id gene))
                            "Confirmed")}]
     (assoc data
       :prose
       (str/join " "
         (those
          ;; Currently confused about where "locus" is meant to come from in Perl code...

          (cond
           (and (:locus data) (:associated_sequence data))
           "This gene has been defined mutationally and associated with a sequence."

           (:associated_sequence data)
           "This gene is known only by sequence."

           (:locus data)
           "This gene is known only by mutation.")

          ;; CGC-name bit doesn't work because both "locus" and "approved name" are missing.

          (cond
           (= (:confirmed data) "Confirmed")
           "Gene structures have been confirmed by a curator."

           (:gene/matching-cdna gene)
           "Gene structures have been confirmed by matching cDNA."

           :default
           "Gene structures have not been confirmed.")))))
   :description "gene type and status"})

(defn- gene-class [gene]
  {:data
   (if-let [class (:gene/gene-class gene)]
     {:tag (pack-obj "gene-class" class)
      :description (str "Datomic: " (first (:gene-class/description class)))})
   :description "The gene class for this gene"})

(defn- gene-operon [gene]
  {:data
   (if-let [operon (->> (:operon.contains-gene/_gene gene)
                        (first)
                        (:operon/_contains_gene))]
     (pack-obj "operon" operon))
   :description "Operon the gene is contained in"})

(defn- concise-description [gene]
  {:data
   (if-let [desc (or (first (:gene/concise-description gene))
                     (first (:gene/automated-description gene))
                     (->> (:gene/corresponding-cds gene)
                          (first)
                          (:cds/brief-identification))
                     (->> (:gene/corresponding-transcript gene)
                          (first)
                          (:transcript/brief-identification)))]
     {:text (some (fn [[k v]] (if (= (name k) "text") v)) desc)
      :evidence (or (get-evidence desc)
                    (get-evidence (first (:gene/provisional-description gene))))})
   :description "A manually curated description of the gene's function"})

(defn- curatorial-remarks [gene]
  {:data
   (->> (:gene/remark gene)
        (map (fn [rem]
               {:text (:gene.remark/text rem)
                :evidence (get-evidence rem)}))
        (seq))
   :description "curatorial remarks for the Gene"})

(defn- legacy-info [gene]
  {:data
   (seq (map :gene.legacy-information/text (:gene/legacy-information gene)))
   :description
   "legacy information from the CSHL Press C. elegans I/II books"})

(defn- named-by [gene]
  {:data
   (->> (:gene/cgc-name gene)
        (get-evidence)
        (mapcat val))
   :description
   "the source where the approved name was first described"})

(defn- parent-sequence [gene]
  {:data
   (pack-obj (:locatable/parent gene))
   :description
   "parent sequence of this gene"})

(defn- parent-clone [gene]
  {:data
   (pack-obj (first (:clone/_positive-gene gene)))
   :description
   "parent clone of this gene"})

(defn- cloned-by [gene]
  {:data
   (if-let [ev (get-evidence (first (:gene/cloned-by gene)))]
     {:cloned_by (key (first ev))
      :tag       (key (first ev))
      :source    (first (val (first ev)))})
   :description
   "the person or laboratory who cloned this gene"})

(defn- transposon [gene]
  {:data
   (pack-obj (first (:gene/corresponding-transposon gene)))
   :description
   "Corresponding transposon for this gene"})

(defn- sequence-name [gene]
  {:data
   (or (:gene/sequence-name gene)
       "unknown")
   :description
   "the primary corresponding sequence name of the gene, if known"})

(defn- locus-name [gene]
  {:data
   (if-let [cgc (:gene/cgc-name gene)]
     (pack-obj "gene" gene :label (:gene.cgc-name/text cgc))
     "not assigned")
   :description "the locus name (also known as the CGC name) of the gene"})

(defn- disease-relevance [gene]
  {:data
   (->> (:gene/disease-relevance gene)
        (map (fn [rel]
               {:text (:gene.disease-relevance/note rel)
                :evidence (get-evidence rel)}))
        (seq))
   :description
   "curated description of human disease relevance"})

(defn- version [gene]
  {:data
   (str (:gene/version gene))
   :description "the current WormBase version of the gene"})

(defn- also-refers-to [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?other-gene ...]
           :in $ ?gene
           :where [?gene :gene/cgc-name ?cgc]
                  [?cgc :gene.cgc-name/text ?cgc-name]
                  [?other-name :gene.other-name/text ?cgc-name]
                  [?other-gene :gene/other-name ?other-name]]
         db (:db/id gene))
      (map #(pack-obj "gene" (entity db %)))
      (seq))
     :description
     "other genes that this locus name may refer to"}))

(defn- merged-into [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find ?merge-partner .
               :in $ ?gene
               :where [?gene :gene/version-change ?vc]
                      [?vc :gene-history-action/merged-into ?merge-partner]]
             db (:db/id gene))
          (entity db)
          (pack-obj "gene"))
     :description "the gene this one has merged into"}))

(defn- get-sd [gene type]
  (let [key     (keyword "gene" type)
        txt-key (keyword (str "gene." type) "text")]
    (->> (key gene)
         (map (fn [data]
                {:text     (txt-key data)
                 :evidence (get-evidence data)}))
         (seq))))

(defn- structured-description [gene]
  {:data
   (vmap
    :Provisional_description
    (let [cds (->> (:gene/concise-description gene)
                   (map :gene.concise-description/text)
                   (set))]
      (seq
       (for [p (:gene/provisional-description gene)
             :let [txt (:gene.provisional-description/text p)]
             :when (not (cds txt))]
         {:text txt
          :evidence (get-evidence p)})))

    :Other_description
    (get-sd gene "other-description")

    :Sequence_features
    (get-sd gene "sequence-features")

    :Functional_pathway
    (get-sd gene "functional-pathway")

    :Functional_physical_interaction
    (get-sd gene "functional-physical-interaction")

    :Molecular_function
    (get-sd gene "molecular-function")

    :Biological_process
    (get-sd gene "biological-process")

    :Expression
    (get-sd gene "expression"))

   :description
   "structured descriptions of gene function"})

(def-rest-widget overview [gene]
  {:name                     (name-field gene)
   :version                  (version gene)
   :classification           (gene-classification gene)
   :also_refers_to           (also-refers-to gene)
   :merged_into              (merged-into gene)
   :gene_class               (gene-class gene)
   :concise_description      (concise-description gene)
   :remarks                  (curatorial-remarks gene)
   :operon                   (gene-operon gene)
   :legacy_information       (legacy-info gene)
   :named_by                 (named-by gene)
   :parent_sequence          (parent-sequence gene)
   :clone                    (parent-clone gene)
   :cloned_by                (cloned-by gene)
   :transposon               (transposon gene)
   :sequence_name            (sequence-name gene)
   :locus_name               (locus-name gene)
   :human_disease_relevance  (disease-relevance gene)
   :structured_description   (structured-description gene)})

;;
;; Phenotypes widget
;;

(def q-gene-rnai-pheno
  '[:find ?pheno (distinct ?ph)
    :in $ ?g
    :where [?gh :rnai.gene/gene ?g]
           [?rnai :rnai/gene ?gh]
           [?rnai :rnai/phenotype ?ph]
           [?ph :rnai.phenotype/phenotype ?pheno]])

(def q-gene-rnai-not-pheno
  '[:find ?pheno (distinct ?ph)
    :in $ ?g
    :where [?gh :rnai.gene/gene ?g]
           [?rnai :rnai/gene ?gh]
           [?rnai :rnai/phenotype-not-observed ?ph]
    [?ph :rnai.phenotype-not-observed/phenotype ?pheno]])

(def q-gene-var-pheno
   '[:find ?pheno (distinct ?ph)
     :in $ ?g
     :where [?gh :variation.gene/gene ?g]
            [?var :variation/gene ?gh]
            [?var :variation/phenotype ?ph]
            [?ph :variation.phenotype/phenotype ?pheno]])
 
(def q-gene-var-not-pheno
   '[:find ?pheno (distinct ?ph)
     :in $ ?g
     :where [?gh :variation.gene/gene ?g]
            [?var :variation/gene ?gh]
            [?var :variation/phenotype-not-observed ?ph]
            [?ph :variation.phenotype-not-observed/phenotype ?pheno]])

(defn- author-lastname [author-holder]
  (or
   (->> (:affiliation/person author-holder)
        (first)
        (:person/last-name))
   (-> (:paper.author/author author-holder)
       (:author/id)
       (str/split #"\s+")
       (last))))

(defn- author-list [paper]
  (let [authors (->> (:paper/author paper)
                     (sort-by :ordered/index))]
    (cond
     (= (count authors) 1)
     (author-lastname (first authors))

     (< (count authors) 6)
     (let [names (map author-lastname authors)]
       (str (str/join ", " (butlast names)) " & " (last names)))

     :default
     (str (author-lastname (first authors)) " et al."))))


(defn- evidence-paper [paper]
  {:class "paper"
   :id (:paper/id paper)
   :taxonomy "all"
   :label (str (author-list paper) ", " (:paper/publication-date paper))})

(defn- phenotype-table [db gene not?]
  (let [var-phenos (into {} (q (if not?
                                 q-gene-var-not-pheno
                                 q-gene-var-pheno)
                               db gene))
        rnai-phenos (into {} (q (if not?
                                  q-gene-rnai-not-pheno
                                  q-gene-rnai-pheno)
                                db gene))
        phenos (set (concat (keys var-phenos)
                            (keys rnai-phenos)))]
    (->>
     (for [pid phenos :let [pheno (entity db pid)]]
       [(:phenotype/id pheno)
        {:object
         {:class "phenotype"
          :id (:phenotype/id pheno)
          :label (:phenotype.primary-name/text (:phenotype/primary-name pheno))
          :taxonomy "all"}
         :evidence
         (vmap
          "Allele:"
          (if-let [vp (seq (var-phenos pid))]
            (for [v vp
                  :let [holder (entity db v)
                        var ((if not?
                               :variation/_phenotype-not-observed
                               :variation/_phenotype)
                             holder)]]
              {:text
               {:class "variation"
                :id (:variation/id var)
                :label (:variation/public-name var)
                :style (if (= (:variation/seqstatus var)
                              :variation.seqstatus/sequence)
                         "font-weight: bold"
                         0)
                :taxonomy "c_elegans"}
               :evidence
               (vmap
                :Person_evidence
                (seq
                 (for [person (:phenotype-info/person-evidence holder)]
                   {:class "person"
                    :id (:person/id person)
                    :label (:person/standard-name person)
                    :taxonomy "all"}))

                :Anatomy_term
                (seq
                 (for [anatomy (:phenotype-info/anatomy-term holder)]
                   (pack-obj "anatomy-term" (:phenotype-info.anatomy-term/anatomy-term anatomy))))

                :Curator_confirmed
                (seq
                 (for [person (:phenotype-info/curator-confirmed holder)]
                   {:class "person"
                    :id (:person/id person)
                    :label (:person/standard-name person)
                    :taxonomy "all"}))

                :Paper_evidence
                (seq
                 (for [paper (:phenotype-info/paper-evidence holder)]
                   (evidence-paper paper)))

                :Remark
                (seq
                 (map :phenotype-info.remark/text
                      (:phenotype-info/remark holder))))}))
          
          "RNAi:"
          (if-let [rp (seq (rnai-phenos pid))]
            (for [r rp
                  :let [holder (entity db r)
                          rnai ((if not?
                                  :rnai/_phenotype-not-observed
                                  :rnai/_phenotype)
                                holder)]]
              {:text
               {:class "rnai"
                :id (:rnai/id rnai)
                :label (:rnai/id rnai)
                :style 0
                :taxonomy "c_elegans"}
               :evidence
               (vmap
                :Genotype (:rnai/genotype rnai)
                :Remark (seq (map :phenotype-info.remark/text
                                  (:phenotype-info/remark holder)))
                :Strain (:strain/id (:rnai/strain rnai))
                :Paper (if-let [paper (:rnai/reference rnai)]
                         (evidence-paper paper)))})))}])
     (into {}))))
      

(defn- phenotype-by-interaction [db gid]
  (let [table (q '[:find ?pheno (distinct ?int) ?int-type
                    :in $ ?gene
                    :where [?ig :interaction.interactor-overlapping-gene/gene ?gene]
                           [?int :interaction/interactor-overlapping-gene ?ig]
                           [?int :interaction/interaction-phenotype ?pheno]
                           [?int :interaction/type ?type-id]
                           [?type-id :db/ident ?int-type]]
                 db gid)
        phenos (->> (map first table)
                    (set)
                    (map (fn [pid]
                           [pid (pack-obj "phenotype" (entity db pid))]))
                    (into {}))
        ints (->> (mapcat second table)
                  (set)
                  (map (fn [iid]
                         (let [int (entity db iid)]
                           [iid
                            {:interaction (pack-obj "interaction" int)
                             :citations (map (partial pack-obj "paper") (:interaction/paper int))}])))
                  (into {}))]
  {:data
   (map (fn [[pheno pints int-type]]
          {:interaction_type
           (humanize-ident int-type)
           
           :phenotype
           (phenos pheno)

           :interactions
           (map #(:interaction (ints %)) pints)

           :citations
           (map #(:citations (ints %)) pints)})
        table)
   :description
   "phenotype based on interaction"}))
        
   
         
(def-rest-widget phenotypes [gene]
  {
   :name      (name-field gene)

   :phenotype
   {:data
    {:Phenotype              (phenotype-table (d/entity-db gene) (:db/id gene) false)
     :Phenotype_not_observed (phenotype-table (d/entity-db gene) (:db/id gene) true)}
    :description
    "The phenotype summary of the gene"}
        
   :phenotype_by_interaction
   (phenotype-by-interaction (d/entity-db gene) (:db/id gene))})
         
;;
;; Mapping data widget
;;

;; Needs better support for non-gene things.

(defn gene-mapping-twopt
  [db id]
  (->> (q '[:find [?tp ...]
            :in $ ?gene
            :where (or-join [?gene ?tp]
                     (and [?tpg1 :two-point-data.gene-1/gene ?gene]
                          [?tp :two-point-data/gene-1 ?tpg1])
                     (and [?tpg2 :two-point-data.gene-2/gene ?gene]
                          [?tp :two-point-data/gene-2 ?tpg2]))]
          db id)
       (map (partial entity db))
       (map
        (fn [tp]
          {:mapper     (pack-obj "author" (first (:two-point-data/mapper tp)))
           :date       (:two-point-data/date tp)
           :raw_data   (:two-point-data/results tp)
           :genotype   (:two-point-data/genotype tp)
           :comment    (str/join "<br>" (map :two-point-data.remark/text (:two-point-data/remark tp)))
           :distance   (format "%s (%s-%s)" (or (:two-point-data/calc-distance tp) "0.0")
                                            (:two-point-data/calc-lower-conf tp)
                                            (:two-point-data/calc-upper-conf tp))
           :point_1    (let [p1 (:two-point-data/gene-1 tp)]
                         [(pack-obj "gene" (:two-point-data.gene-1/gene p1))
                          (pack-obj "variation" (:two-point-data.gene-1/variation p1))])
           :point_2    (let [p2 (:two-point-data/gene-2 tp)]
                         [(pack-obj "gene" (:two-point-data.gene-2/gene p2))
                          (pack-obj "variation" (:two-point-data.gene-2/variation p2))])}
          ))))

(defn gene-mapping-posneg
  [db id]
  (->> (q '[:find [?pn ...]
            :in $ ?gene
            :where (or-join [?gene ?pn]
                      (and [?png1 :pos-neg-data.gene-1/gene ?gene]
                           [?pn :pos-neg-data/gene-1 ?png1])
                      (and [?png2 :pos-neg-data.gene-2/gene ?gene]
                           [?pn :pos-neg-data/gene-2 ?png2]))]
          db id)
       (map (partial entity db))
       (map
        (fn [pn]
          (let [items (->> [(pack-obj ((some-fn (comp :pos-neg-data.gene-1/gene :pos-neg-data/gene-1)
                                                (comp :pos-neg-data.locus-1/locus :pos-neg-data/locus-1)
                                                :pos-neg-data/allele-1
                                                :pos-neg-data/clone-1
                                                :pos-neg-data/rearrangement-1)
                                       pn))
                            (pack-obj ((some-fn (comp :pos-neg-data.gene-2/gene :pos-neg-data/gene-2)
                                                (comp :pos-neg-data.locus-2/locus :pos-neg-data/locus-2)
                                                :pos-neg-data/allele-2
                                                :pos-neg-data/clone-2
                                                :pos-neg-data/rearrangement-2)
                                       pn))]
                           (map (juxt :label identity))
                           (into {}))
                result (str/split (:pos-neg-data/results pn) #"\s+")]
          {:mapper    (pack-obj "author" (first (:pos-neg-data/mapper pn)))
           :date      (:pos-neg-data/date pn)
           :result    (map #(or (items (str/replace % #"\." ""))
                                (str " " % " "))
                           result)})
           
          ))))
          
(defn gene-mapping-multipt
  [db id]
  (->> (q '[:find [?mp ...]
            :in $ % ?gene
            :where (mc-obj ?mc ?gene)
            (or
             [?mp :multi-pt-data/a-non-b ?mc]
             [?mp :multi-pt-data/b-non-a ?mc]
             [?mp :multi-pt-data/combined ?mc])]
          db
          '[[(mc-obj ?mc ?gene) [?mcg :multi-counts.gene/gene ?gene]
                                [?mc :multi-counts/gene ?mcg]]
            [(mc-obj ?mc ?gene) (mc-obj ?mc2 ?gene)
                                [?mc :multi-counts/gene ?mc2]]]
          id)
       (map (partial entity db))
       (map
        (fn [mp]
          {:comment  (->> mp
                          :multi-pt-data/remark
                          first
                          :multi-pt-data.remark/text)
           :mapper   (pack-obj "author" (first (:multi-pt-data/mapper mp)))
           :date     (:multi-pt-data/date mp)
           :genotype (:multi-pt-data/genotype mp)
           :result   (let [res (loop [node (first (:multi-pt-data/combined mp))
                                      res  []]
                                 (cond
                                  (:multi-counts/gene node)
                                  (let [obj (:multi-counts/gene node)]
                                    (recur obj (conj res [(:multi-counts.gene/gene obj)
                                                          (:multi-counts.gene/int obj)])))
                                  
                                  :default res))
                           tot (->> (map second res)
                                    (filter identity)
                                    (reduce +))]
                       (->>
                        (mapcat
                         (fn [[obj count]]
                           [(pack-obj obj)
                            (if count
                              (str " (" count "/" tot ") "))])
                         res)
                        (filter identity)))}
          

          ))))
    

(def-rest-widget mapping-data [gene]
  {:name      (name-field gene)

   :two_pt_data
   {:data (seq (gene-mapping-twopt (d/entity-db gene) (:db/id gene)))
    :description "Two point mapping data for this gene"}

   :pos_neg_data
   {:data (seq (gene-mapping-posneg (d/entity-db gene) (:db/id gene)))
    :description "Positive/Negative mapping data for this gene"}

   :multi_pt_data
   {:data (seq (gene-mapping-multipt (d/entity-db gene) (:db/id gene)))
    :description "Multi point mapping data for this gene"}})


;;
;; Human diseases widget
;;

(defn- disease-models [gene]
  (let [db (d/entity-db gene)]
    {:data
     {:potential_model
      (seq
       (for [d (:gene/disease-potential-model gene)]
         (assoc (pack-obj (:gene.disease-potential-model/do-term d))
           :ev (get-evidence d))))

      :experimental_model
      (seq
       (for [d (:gene/disease-experimental-model gene)]
         (assoc (pack-obj (:gene.disease-experimental-model/do-term d))
           :ev (get-evidence d))))
        
      :gene
      (seq 
       (q '[:find [?o ...]
            :in $ ?gene
            :where [?gene :gene/database ?dbent]
                   [?omim :database/id "OMIM"]
                   [?field :database-field/id "gene"]
                   [?dbent :gene.database/database ?omim]
                   [?dbent :gene.database/field ?field]
                   [?dbent :gene.database/accession ?o]]
          db (:db/id gene)))

      :disease
      (seq 
       (q '[:find [?o ...]
            :in $ ?gene
            :where [?gene :gene/database ?dbent]
                   [?omim :database/id "OMIM"]
                   [?field :database-field/id "disease"]
                   [?dbent :gene.database/database ?omim]
                   [?dbent :gene.database/field ?field]
                   [?dbent :gene.database/accession ?o]]
          db (:db/id gene)))}}))
       

(def-rest-widget human-diseases [gene]
  {:name                    (name-field gene)
   :human_disease_relevance (disease-relevance gene)
   :human_diseases          (disease-models gene)})

;;
;; Assembly-twiddling stuff (should be in own namespace?)
;;

#_(defn locatable-root-segment [loc]
  (loop [parent (:locatable/parent loc)
         min    (:locatable/min    loc)
         max    (:locatable/max    loc)]
    (if parent
      (if-let [ss (first (:sequence.subsequence/_sequence parent))]
        (recur (:sequence/_subsequence ss)
               (+ min (:sequence.subsequence/start ss) -1)
               (+ max (:sequence.subsequence/start ss) -1))
        {:sequence (:sequence/id parent)
         :seq-id   (:db/id parent)
         :min      min
         :max      max}))))
      

;;
;; Reagents widget
;;

(defn- construct-labs [construct]
  (seq (map #(pack-obj "laboratory" (:construct.laboratory/laboratory %))
            (:construct/laboratory construct))))

(defn- transgene-labs [tg]
  (seq (map #(pack-obj "laboratory" (:transgene.laboratory/laboratory %))
            (:transgene/laboratory tg))))
  

(defn- transgene-record [construct]
  (let [base {:construct (pack-obj "construct" construct)
              :used_in   (pack-obj "transgene" (first (:construct/transgene-construct construct)))
              :use_summary (first (:construct/summary construct))}]
    (cond-let [use]
      (:construct/transgene-construct construct)
      (for [t use]
        (assoc base :used_in_type "Transgene construct"
                    :use_summary (:transgene/summary t)
                    :used_in     (pack-obj "transgene" t)
                    :use_lab     (or (transgene-labs t)
                                     (construct-labs construct))))

      (:construct/transgene-coinjection construct)
      (for [t use]
        (assoc base :used_in_type "Transgene coinjection"
                    :use_summary (:transgene/summary t)
                    :used_in     (pack-obj "transgene" t)
                    :use_lab     (or (transgene-labs t)
                                     (construct-labs construct))))

      (:construct/engineered-variation construct)
      (for [v use]
        (assoc base :used_in_type "Engineered variation"
                    :used_in      (pack-obj "variation" v)
                    :use_lab      (construct-labs construct))))))

(defn- transgenes [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?cons ...]
               :in $ ?gene
               :where [?cbg :construct.driven-by-gene/gene ?gene]
                      [?cons :construct/driven-by-gene ?cbg]]
             db (:db/id gene))
          (map (partial entity db))
          (mapcat transgene-record)
          (seq))
     :description "transgenes expressed by this gene"}))
                      
(defn- transgene-products [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?cons ...]
               :in $ ?gene
               :where [?cg :construct.gene/gene ?gene]
                      [?cons :construct/gene ?cg]]
             db (:db/id gene))
          (map (partial entity db))
          (mapcat transgene-record)
          (seq))
     :description "transgenes that express this gene"}))

(def ^:private probe-types
  {:oligo-set.type/affymetrix-microarray-probe "Affymetrix"
   :oligo-set.type/washu-gsc-microarray-probe  "GSC"
   :oligo-set.type/agilent-microarray-probe    "Agilent"})

(defn- microarray-probes [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?oligo ...]
               :in $ ?gene [?type ...]
               :where [?gene :gene/corresponding-cds ?gcds]
                      [?gcds :gene.corresponding-cds/cds ?cds]
                      [?ocds :oligo-set.overlaps-cds/cds ?cds]
                      [?oligo :oligo-set/overlaps-cds ?ocds]
                      [?oligo :oligo-set/type ?type]]
             db (:db/id gene) (keys probe-types))
          (map (fn [oid]
                 (let [oligo (entity db oid)]
                   (assoc (pack-obj "oligo-set" oligo)
                     :class "pcr_oligo"
                     :label (format
                             "%s [%s]"
                             (:oligo-set/id oligo)
                             (some probe-types (:oligo-set/type oligo)))))))
          (seq))
     :description "microarray probes"}))

(defn- matching-cdnas [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?cdna ...]
               :in $ ?gene
               :where [?gene :gene/corresponding-cds ?gcds]
                      [?gcds :gene.corresponding-cds/cds ?cds]
                      [?cds :cds/matching-cdna ?mcdna]
                      [?mcdna :cds.matching-cdna/sequence ?cdna]]
             db (:db/id gene))
          (map #(pack-obj "sequence" (entity db %)))
          (seq))
     :description "cDNAs matching this gene"}))
             
(defn- antibodies [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?ab ...]
               :in $ ?gene
               :where [?gab :antibody.gene/gene ?gene]
                      [?ab :antibody/gene ?gab]]
             db (:db/id gene))
          (map
           (fn [abid]
             (let [ab (entity db abid)]
               {:antibody (pack-obj "antibody" ab)
                :summary (:antibody.summary/text (first (:antibody/summary ab)))
                :laboratory (map (partial pack-obj "laboratory") (:antibody/location ab))})))
          (seq))
     :description "antibodies generated against protein products or gene fusions"}))

#_(def ^:private child-rule  '[[(child ?parent ?min ?max ?method ?c) [(pseudoace.binning/reg2bins ?min ?max) [?bin ...]]
                                                                   [(pseudoace.binning/xbin ?parent ?bin) ?xbin]
                                                                   [?c :locatable/xbin ?xbin]
                                                                   [?c :locatable/parent ?parent]
                                                                   [?c :pcr-product/method ?method]
                                                                   [?c :locatable/min ?cmin]
                                                                   [?c :locatable/max ?cmax]
                                                                   [(<= ?cmin ?max)]
                                                                   [(>= ?cmax ?min)]]])

(def ^:private child-rule  
  '[[(child ?parent ?min ?max ?method ?c) [?parent :sequence/id ?seq-name]
                                          [(wb.binning/bins ?seq-name ?min ?max) [?bin ...]]
                                          [?c :locatable/murmur-bin ?bin]
                                          [?c :locatable/parent ?parent]
                                          [?c :locatable/min ?cmin]
                                          [?c :locatable/max ?cmax]
                                          [?c :pcr-product/id _]
                                          [?c :locatable/method ?method]
                                          [(<= ?cmin ?max)]
                                          [(>= ?cmax ?min)]]])

(defn- orfeome-primers [gene]
  (let [db  (d/entity-db gene)
        [parent start end] (root-segment gene)]
    {:data
     ;;
     ;; Big assembly-navigation query should probably be factored out somewhere
     ;; once we're a bit more solid about how this stuff should work.
     ;;
     (if parent
       (->> (q '[:find [?p ...] 
                 :in $ % ?seq ?min ?max 
                 :where [?method :method/id "Orfeome"] 
                        (or-join [?seq ?min ?max ?method ?p]
                          (and         
                            [?ss-seq :locatable/assembly-parent ?seq]
                            [?ss-seq :locatable/min ?ss-min]
                            [?ss-seq :locatable/max ?ss-max]
                            [(<= ?ss-min ?max)]
                            [(>= ?ss-max ?min)]
                            [(- ?min ?ss-min -1) ?rel-min]
                            [(- ?max ?ss-min -1) ?rel-max]
                            (child ?ss-seq ?rel-min ?rel-max ?method ?p))
                          (child ?seq ?min ?max ?method ?p))]
               db
               child-rule
               (:db/id parent) start end)
            (map
             (fn [ppid]
               (let [pp (entity db ppid)]
                 {:id    (:pcr-product/id pp)
                :class "pcr_oligo"
                  :label (:pcr-product/id pp)})))
            (seq)))
     :description "ORFeome Project primers and sequences"}))

(defn- primer-pairs [gene]
  (let [db                 (d/entity-db gene)
        [parent start end] (root-segment gene)]
    {:data
     (if parent
       (->> (q '[:find [?p ...] 
                 :in $ % ?seq ?min ?max 
                 :where [?method :method/id "GenePairs"] 
                        (or-join [?seq ?min ?max ?method ?p]
                          (and         
                            [?ss-seq :locatable/assembly-parent ?seq]
                            [?ss-seq :locatable/min ?ss-min]
                            [?ss-seq :locatable/max ?ss-max]
                            [(<= ?ss-min ?max)]
                            [(>= ?ss-max ?min)]
                            [(- ?min ?ss-min -1) ?rel-min]
                            [(- ?max ?ss-min -1) ?rel-max]
                            (child ?ss-seq ?rel-min ?rel-max ?method ?p))
                          (child ?seq ?min ?max ?method ?p))]
               db
               child-rule
               (:db/id parent) start end)
            (map
             (fn [ppid]
               (let [pp (entity db ppid)]
                 {:id    (:pcr-product/id pp)
                  :class "pcr_oligo"
                  :label (:pcr-product/id pp)})))
            (seq)))
     :description "Primer pairs"}))

(defn- sage-tags [gene]
  {:data
   (seq (map #(pack-obj "sage-tag" (:sage-tag/_gene %)) (:sage-tag.gene/_gene gene)))

   :description
   "SAGE tags identified"})

(def-rest-widget reagents [gene]
  {:name               (name-field gene)
   :transgenes         (transgenes gene)
   :transgene_products (transgene-products gene)
   :microarray_probes  (microarray-probes gene)
   :matching_cdnas     (matching-cdnas gene)
   :antibodies         (antibodies gene)
   :orfeome_primers    (orfeome-primers gene)
   :primer_pairs       (primer-pairs gene)
   :sage_tags          (sage-tags gene)})

;;
;; New style GO widget
;;

(def ^:private division-names
  {:go-term.type/molecular-function "Molecular_function"
   :go-term.type/cellular-component "Cellular_component"
   :go-term.type/biological-process "Biological_process"})

(defn- term-table-full [db annos]
  (map
   (fn [{:keys [term code anno]}]
     {:anno_id
      (:go-annotation/id anno)

      :extensions
      (->>
       (concat
        (for [{rel :go-annotation.molecule-relation/text
               mol :go-annotation.molecule-relation/molecule}
              (:go-annotation/molecule-relation anno)]
          [rel (pack-obj "molecule" mol)]))
       (reduce
        (fn [m [rel obj]]
          (assoc m rel obj))
        nil))

      :with
      (seq
       (concat
        (map (partial pack-obj "gene")      (:go-annotation/interaction-gene anno))
        (map (partial pack-obj "go-term")   (:go-annotation/inferred-from-go-term anno))
        (map (partial pack-obj "motif")     (:go-annotation/motif anno))
        (map (partial pack-obj "rnai")      (:go-annotation/rnai-result anno))
        (map (partial pack-obj "variation") (:go-annotation/variation anno))
        (map (partial pack-obj "phenotype") (:go-annotation/phenotype anno))
        ;; Also DB fields...
        ))
        
      
      :evidence_code
      {:text
       (:go-code/id code)
       
       :evidence
       (vmap
        :Date_last_updated
        (if-let [d (:go-annotation/date-last-updated anno)]
          (str d))

        :Contributed_by
        (seq (map (partial pack-obj "analysis")
                  (:go-annotation/contributed-by anno)))
        
        :Reference
        (seq (map (partial pack-obj "paper")
                  (:go-annotation/reference anno))))}

      :term
      nil  ;; WTF?

      :term_id
      (pack-obj "go-term" term :label (:go-term/id term))

      :term_description
      (pack-obj "go-term" term)})
   annos))

(defn- gene-ontology-full [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find ?div ?term ?code ?anno
           :in $ ?gene
           :where [?anno :go-annotation/gene ?gene]
                  [?anno :go-annotation/go-term ?term]
                  [?anno :go-annotation/go-code ?code]
                  [?term :go-term/type ?tdiv]
                  [?tdiv :db/ident ?div]]
         db (:db/id gene))
      (map
       (fn [[div term code anno]]
         {:division div
          :term     (entity db term)
          :code     (entity db code)
          :anno     (entity db anno)}))
      (group-by :division)
      (map
       (fn [[key annos]]
         [(division-names key)
          (term-table-full db annos)]))
      (into {}))

     :description
     "gene ontology associations"}))

(defn- term-summary-table [db annos]
  (->>
   (group-by :term annos)
   (map
    (fn [[term annos]]
      {:extensions       nil

       :term_id
       (pack-obj "go-term" term :label (:go-term/id term))
       
       :term_description
       (pack-obj "go-term" term)}))))

(defn- gene-ontology-summary [gene]
 (let [db (d/entity-db gene)]
  {:data
   (->>
    (q '[:find ?div ?term ?anno
         :in $ ?gene
         :where [?anno :go-annotation/gene ?gene]
                [?anno :go-annotation/go-term ?term]
                [?term :go-term/type ?tdiv]
                [?tdiv :db/ident ?div]]
       db (:db/id gene))
    (map
     (fn [[div term code anno]]
       {:division div
        :term     (entity db term)
        :anno     (entity db anno)}))
    (group-by :division)
    (map
     (fn [[key annos]]
       [(division-names key)
        (term-summary-table db annos)]))
    (into {}))
   
   :description
   "gene ontology associations"}))

(def-rest-widget gene-ontology [gene]
  {:name                   (name-field gene)
   :gene_ontology_summary  (gene-ontology-summary gene)
   :gene_ontology          (gene-ontology-full gene)})

;;
;; Expression widget
;;

(defn- anatomy-terms [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?at ...]
               :in $ ?gene
               :where [?epg :expr-pattern.gene/gene ?gene]
                      [?ep :expr-pattern/gene ?epg]
                      [?ep :expr-pattern/anatomy-term ?epa]
                      [?epa :expr-pattern.anatomy-term/anatomy-term ?at]]
             db (:db/id gene))
          (map (fn [at-id]
                 (pack-obj "anatomy-term" (entity db at-id)))))
     :description "anatomy terms from expression patterns for the gene"}))

(defn- expr-pattern-type [ep]
  (some (set (keys ep)) [:expr-pattern/reporter-gene
                         :expr-pattern/in-situ
                         :expr-pattern/antibody
                         :expr-pattern/northern
                         :expr-pattern/western
                         :expr-pattern/rt-pcr
                         :expr-pattern/localizome
                         :expr-pattern/microarray
                         :expr-pattern/tiling-array
                         :expr-pattern/epic
                         :expr-pattern/cis-regulatory-element]))

(defn- expression-patterns [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?ep ...]
           :in $ ?gene
           :where [?epg :expr-pattern.gene/gene ?gene]
                  [?ep :expr-pattern/gene ?epg]
                  (not
                    [?ep :expr-pattern/microarray _])
                  (not
                    [?ep :expr-pattern/tiling-array _])]
         db (:db/id gene))
      (map 
       (fn [ep-id]
         (let [ep (entity db ep-id)]
           (vmap
            :expression_pattern
            (pack-obj "expr-pattern" ep)

            :description
            (if-let [desc (or (:expr-pattern/pattern ep)
                              (:expr-pattern/subcellular-localization ep)
                              (:expr-pattern.remark/text (:expr-pattern/remark ep)))]
              {:text desc
               :evidence (vmap
                          :Reference (pack-obj "paper" (first (:expr-pattern/reference ep))))})

            :type
            (humanize-ident (expr-pattern-type ep))

            :expressed_in
            (map #(pack-obj "anatomy-term" (:expr-pattern.anatomy-term/anatomy-term %))
                 (:expr-pattern/anatomy-term ep))

            :life_stage
            (map #(pack-obj "life-stage" (:expr-pattern.life-stage/life-stage %))
                 (:expr-pattern/life-stage ep))

            :go_term
            (if-let [go (:expr-pattern/go-term ep)]
              {:text (map #(pack-obj "go-term" (:expr-pattern.go-term/go-term %)) go)
               :evidence {"Subcellular localization" (:expr-pattern/subcellular-localization ep)}})

            :transgene
            (if (:expr-pattern/transgene ep)
              (map
               (fn [tg]
                 (let [packed (pack-obj "transgene" tg)
                       cs     (:transgene/construction-summary tg)]
                   (if cs
                     {:text packed
                      :evidence {:Construction_summary cs}}
                     packed)))
               (:expr-pattern/transgene ep))
              (map
               (fn [cons]
                 (let [packed (pack-obj "construct" cons)
                       cs     (:construct/construction-summary cons)]
                   (if cs
                     {:text packed
                      :evidence {:Construction_summary cs}}
                     packed)))
               (:expr-pattern/construct ep)))))
                   
           )))
     :description (format "expression patterns associated with the gene:%s" (:gene/id gene))}))
              

(defn- expression-clusters [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?ec ...]
           :in $ ?gene
           :where [?ecg :expression-cluster.gene/gene ?gene]
                  [?ec :expression-cluster/gene ?ecg]]
         db (:db/id gene))
      (map
       (fn [ec-id]
         (let [ec (entity db ec-id)]
           {:expression_cluster (pack-obj "expression-cluster" ec)
            :description        (:expression-cluster/description ec)}))))
     :description
     "expression cluster data"}))

(def-rest-widget expression [gene]
  {:name                (name-field gene)
   :anatomy_terms       (anatomy-terms gene)
   :expression_patterns (expression-patterns gene)
   :expression_cluster  (expression-clusters gene)})

;;
;; Homology widget
;;

(defn- pack-ortholog [db oid]
  (let [ortho (entity db oid)]
    {:ortholog (pack-obj "gene" (:gene.ortholog/gene ortho))
     :species (if-let [[_ genus species] (re-matches #"^(\w)\w*\s+(.*)"
                                                     (:species/id (:gene.ortholog/species ortho)))]
                {:genus genus :species species})
     :method (map (partial pack-obj) (:evidence/from-analysis ortho))}))

(defn- homology-orthologs [gene species]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?ortho ...]
           :in $ ?gene [?species-id ...]
           :where [?gene :gene/ortholog ?ortho]
                  [?ortho :gene.ortholog/species ?species]
                  [?species :species/id ?species-id]]
         db (:db/id gene) species)
      (map (partial pack-ortholog db)))
   :description
   "precalculated ortholog assignments for this gene"}))

(defn- homology-orthologs-not [gene species]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?ortho ...]    ;; Look into why this can't be done with Datomic "not"
           :in $ ?gene ?not-species
           :where [?gene :gene/ortholog ?ortho]
                  [?ortho :gene.ortholog/species ?species]
                  [?species :species/id ?species-id]
                  [(get ?not-species ?species-id :dummy) ?smember]
                  [(= ?smember :dummy)]]
         db (:db/id gene) (set species))
      (map (partial pack-ortholog db)))
   :description
     "precalculated ortholog assignments for this gene"}))

(defn- homology-paralogs [gene]
  {:data
   (map
    (fn [para]
      {:ortholog (pack-obj "gene" (:gene.paralog/gene para))
       :species (if-let [[_ genus species] (re-matches #"^(\w)\w*\s+(.*)"
                                                       (:species/id (:gene.paralog/species para)))]
                  {:genus genus :species species})
       :method (map (partial pack-obj) (:evidence/from-analysis para))})
    (:gene/paralog gene))
   :description
   "precalculated ortholog assignments for this gene"})

(defn- protein-domains [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?motif ...]
           :in $ ?gene
           :where [?gene :gene/corresponding-cds ?gcds]
                  [?gcds :gene.corresponding-cds/cds ?cds]
                  [?cds :cds/corresponding-protein ?cprot]
                  [?cprot :cds.corresponding-protein/protein ?prot]
                  [?homol :locatable/parent ?prot]
                  [?homol :homology/motif ?motif]
                  [?motif :motif/id ?mid]
                  [(.startsWith ^String ?mid "INTERPRO:")]]
         db (:db/id gene))
      (map
       (fn [motif-id]
         (let [motif (entity db motif-id)]
           [(first (:motif/title motif))
            (pack-obj "motif" motif)])))
      (into {}))
     :description
     "protein domains of the gene"}))

(defn- best-blastp-matches [gene]
  (let [db    (d/entity-db gene)]
    (if-let [[prot length]
             (->> (q '[:find ?prot ?pep-len
                       :in $ ?gene
                       :where [?gene :gene/corresponding-cds ?gcds]
                              [?gcds :gene.corresponding-cds/cds ?cds]
                              [?cds :cds/corresponding-protein ?cprot]
                              [?cprot :cds.corresponding-protein/protein ?prot]
                              [?prot :protein/peptide ?pep]
                              [?pep :protein.peptide/length ?pep-len]]
                     db (:db/id gene))
                  (sort-by second)
                  (last))]   ; longest protein
      {:data
       {:hits
       (->>
        (q '[:find ?prot ?score ?min ?max
             :in $ ?ref
             :where [?homol :locatable/parent ?ref]
                    [?homol :homology/protein ?prot]
                    [?homol :locatable/score ?score]
                    [?homol :locatable/min ?min]
                    [?homol :locatable/max ?max]]
           db prot)
        (group-by (partial take 2))   ; ?prot and ?score as groupers
        (map
         (fn [[[prot score] lines]]
           (let [prot (entity db prot)]
             {:protein prot
              :species (:protein/species prot)
              :score score
              :coverage (reduce + (map (fn [[_ _ min max]] (- max min)) lines))})))
        (remove #(.startsWith (:protein/id (:protein %)) "MSP"))
        (group-by :species)
        (map
         (fn [[_ hits]]
           (let [{:keys [protein species score coverage]}
                 (->> (sort-by :score hits)
                      (last))]
             {:evalue (format "%7.3g" (Math/pow 10 (- score)))
              :percent (format "%2.1f%%" (float (* 100 (/ coverage length))))
              :taxonomy (if-let [[_ g spec] (re-matches #"(.).* +(.+)" (or (:species/id species) ""))]
                          {:genus   g
                           :species spec})
              :hit (pack-obj protein)
              :description
              (or
               (:protein/description protein)
               (:protein/gene-name protein)
               (->> (:cds.corresponding-protein/_protein protein)
                    (first)
                    (:cds/_corresponding-protein)
                    (:cds/brief-identification)
                    (:cds.brief-identification/text))
               "unknown")}))))}
       :description
       "best BLASTP hits from selected species"}
      {:data         nil
       :description  "no proteins found, no best blastp hits to display"})))
                          
           
                      
(def nematode-species
  ["Ancylostoma ceylanicum"
   "Ascaris suum"
   "Brugia malayi"
   "Bursaphelenchus xylophilus"
   "Caenorhabditis angaria"
   "Caenorhabditis brenneri"
   "Caenorhabditis briggsae"
   "Caenorhabditis elegans"
   "Caenorhabditis japonica"
   "Caenorhabditis remanei"
   "Caenorhabditis sp. 5"
   "Caenorhabditis tropicalis"
   "Dirofilaria immitis"
   "Haemonchus contortus"
   "Heterorhabditis bacteriophora"
   "Loa loa"
   "Meloidogyne hapla"
   "Meloidogyne incognita"
   "Necator americanus"
   "Onchocerca volvulus"
   "Panagrellus redivivus"
   "Pristionchus exspectatus"
   "Pristionchus pacificus"
   "Strongyloides ratti"
   "Trichinella spiralis"
   "Trichuris suis"])

(def-rest-widget homology [gene]
  {:name                (name-field gene)
   :nematode_orthologs  (homology-orthologs gene nematode-species)
   :human_orthologs     (homology-orthologs gene ["Homo sapiens"])
   :other_orthologs     (homology-orthologs-not gene (conj nematode-species "Homo sapiens"))
   :paralogs            (homology-paralogs gene)
   :best_blastp_matches (best-blastp-matches gene)
   :protein_domains     (protein-domains gene)})
   
;;
;; History widget
;;

(defn- history-events [gene]
  {:data
   (->>
    (:gene/version-change gene)
    (mapcat
     (fn [h]
       (let [result {:version (:gene.version-change/version h)
                     :date    (:gene.version-change/date h)
                     :curator (pack-obj "person" (:gene.version-change/person h))
                     :remark  nil
                     :gene    nil
                     :action  "Unknown"}]
         (those
           (if (:gene-history-action/created h)
             (assoc result :action "Created"))

           (if (:gene-history-action/killed h)
             (assoc result :action "Killed"))

           (if (:gene-history-action/suppressed h)
             (assoc result :action "Suppressed"))

           (if (:gene-history-action/resurrected h)
             (assoc result :action "Resurrected"))

           (if (:gene-history-action/transposon-in-origin h)
             (assoc result :action "Transposon_in_origin"))

           (if (:gene-history-action/changed-class h)
             (assoc result :action "Changed_class"))

           (if-let [info (:gene-history-action/merged-into h)]
             (assoc result :action "Merged_into"
                    :gene (pack-obj "gene" info)))
           
           (if-let [info (:gene-history-action/acquires-merge h)]
             (assoc result :action "Acquires_merge"
                    :gene (pack-obj "gene" info)))

           (if-let [info (:gene-history-action/imported h)]
             (assoc result :action "Imported"
                    :remark (first info)))

           (if-let [name (:gene-history-action/cgc-name-change h)]
             (assoc result :action "CGC_name" :remark name))

           (if-let [name (:gene-history-action/other-name-change h)]
             (assoc result :action "Other_name" :remark name))

           (if-let [name (:gene-history-action/sequence-name-change h)]
             (assoc result :action "Sequence_name" :remark name)))))))
   :description
   "the historical annotations of this gene"})


(defn- old-annot [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?historic ...]
               :in $ ?gene
               :where (or
                       [?gene :gene/corresponding-cds-history ?historic]
                       [?gene :gene/corresponding-pseudogene-history ?historic]
                       [?gene :gene/corresponding-transcript-history ?historic])]
             db (:db/id gene))
          (map (fn [hid]
                 (let [hobj (pack-obj (entity db hid))]
                   {:class (:class hobj)
                    :name hobj})))
          (seq))
     :description "the historical annotations of this gene"}))

(def-rest-widget history [gene]
  {:name      (name-field gene)
   :history   (history-events gene)
   :old_annot (old-annot gene)})


;;
;; Sequence widget
;;

(defn- gene-models [gene]      ;; Probably needs more testing for non-coding/pseudogene/etc. cases.
  (let [db      (d/entity-db gene)
        coding? (:gene/corresponding-cds gene)
        seqs (q '[:find [?seq ...]
                  :in $ % ?gene
                  :where (or
                          (gene-transcript ?gene ?seq)
                          (gene-cdst-or-cds ?gene ?seq)
                          (gene-pseudogene ?gene ?seq))]
                db
                '[[(gene-transcript ?gene ?seq) [?gene :gene/corresponding-transcript ?ct]
                                                [?ct :gene.corresponding-transcript/transcript ?seq]]
                  ;; Per Perl code, take transcripts if any exist, otherwise take the CDS itself.
                  [(gene-cdst-or-cds ?gene ?seq) [?gene :gene/corresponding-cds ?cc]
                                                 [?cc :gene.corresponding-cds/cds ?cds]
                                                 [?ct :transcript.corresponding-cds/cds ?cds]
                                                 [?seq :transcript/corresponding-cds ?ct]]
                  [(gene-cdst-or-cds ?gene ?seq) [?gene :gene/corresponding-cds ?cc]
                                                 [?cc :gene.corresponding-cds/cds ?seq]
                                                 (not [_ :transcript.corresponding-cds/cds ?seq])]
                  [(gene-pseudogene ?gene ?seq) [?gene :gene/corresponding-pseudogene ?cp]
                                                [?cp :gene.corresponding-pseudogene/pseudogene ?seq]]]
                (:db/id gene))]
    {:data
      (->>
       (map (partial entity db) seqs)
       (sort-by (some-fn :cds/id :transcript/id :pseudogene/id))
       (reduce
        (fn [{:keys [table remark-map]} sequence]
          (let [cds (or
                     (and (:cds/id sequence) sequence)
                     (:transcript.corresponding-cds/cds (:transcript/corresponding-cds sequence))
                     (:pseudogene.corresponding-cds/cds (:psuedogene/corresponding-cds sequence)))
                protein (:cds.corresponding-protein/protein (:cds/corresponding-protein cds))
                seqs (or (seq (map :transcript.corresponding-cds/_cds (:transcript/_corresponding-cds cds)))
                         [sequence])
                status (str (humanize-ident (:cds/prediction-status cds))
                            (if (:cds/matching-cdna cds)
                              " by cDNA(s)"))
                {:keys [remark-map footnotes]}
                (reduce (fn [{:keys [remark-map footnotes]} r]
                          (let [pr (if-let [ev (get-evidence r)]
                                     {:text     (:cds.remark/text r)
                                      :evidence ev}
                                     (:cds.remark/text r))]
                            (if-let [n (get remark-map pr)]
                              {:remark-map remark-map
                               :footnotes  (conjv footnotes n)}
                              (let [n (inc (count remark-map))]
                                {:remark-map (assoc remark-map pr n)
                                 :footnotes  (conjv footnotes n)}))))
                        {:remark-map remark-map}
                        (:cds/remark cds))]
            {:remark-map
             remark-map
             :table
             (conjv table
              (vmap
               :model
               (map pack-obj seqs)
               
               :protein
               (pack-obj "protein" protein)
                
               :cds
               (vmap
                :text (vassoc (pack-obj "cds" cds) :footnotes footnotes)
                :evidence (if (not (empty? status))
                            {:status status}))
               
               :length_spliced
               (if coding?
                 (if-let [exons (seq (:cds/source-exons cds))]
                   (->> (map (fn [ex]
                               (- (:cds.source-exons/end ex)
                                  (:cds.source-exons/start ex)
                                  -1))
                             exons)
                        (reduce +))))

               :length_unspliced
               (str/join
                "<br>"
                (for [s seqs]
                  (if (and (:locatable/max s) (:locatable/min s))
                    (- (:locatable/max s) (:locatable/min s))
                    "-")))
               
               :length_protein
               (:protein.peptide/length (:protein/peptide protein))
             
               :type (if seqs
                       (if-let [mid (:method/id
                                     (or (:transcript/method sequence)
                                         (:pseudogene/method sequence)
                                         (:cds/method sequence)))]
                         (str/replace mid #"_" " ")))))}))
        {})
       ((fn [{:keys [table remark-map]}]
         (vmap
          :table table
          :remarks (if-not (empty? remark-map)
                     (into (sorted-map)
                           (for [[r n] remark-map]
                             [n r])))))))
                       
                        
           
     :description
     "gene models for this gene"}))

(def-rest-widget sequences [gene]
  {:name         (name-field gene)
   :gene_models  (gene-models gene)})

;;
;; Sequence Features widget
;;

(defn- associated-features [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->>
      (q '[:find [?f ...]
           :in $ ?gene
           :where [?fg :feature.associated-with-gene/gene ?gene]
                  [?f :feature/associated-with-gene ?fg]]
         db (:db/id gene))
      (map
       (fn [fid]
         (let [feature (entity db fid)]
           (vmap
            :name (pack-obj "feature" feature)
            :description (first (:feature/description feature))
            :method (-> (:feature/method feature)
                        (:method/id))
            :interaction (->> (:interaction.feature-interactor/_feature feature)
                              (map #(pack-obj "interaction" (:interaction/_feature-interactor %)))
                              (seq))
            :expr_pattern (->>
                           (q '[:find [?e ...]
                                :in $ ?f
                                :where [?ef :expr-pattern.associated-feature/feature ?f]
                                       [?e :expr-pattern/associated-feature ?ef]
                                       [?e :expr-pattern/anatomy-term _]]
                              db fid)
                           (map
                            (fn [eid]
                              (let [expr (entity db eid)]
                                {:text (map #(pack-obj "anatomy-term" (:expr-pattern.anatomy-term/anatomy-term %))
                                            (:expr-pattern/anatomy-term expr))
                                 :evidence {:by (pack-obj "expr-pattern" expr)}})))
                           (seq))
            :bound_by (->> (:feature/bound-by-product-of feature)
                           (map #(pack-obj "gene" (:feature.bound-by-product-of/gene %)))
                           (seq))
            :tf  (pack-obj "transcription-factor" (:feature/transcription-factor feature))))))
      (seq))
     :description
     "Features associated with this Gene"}))

(def-rest-widget features [gene]
  {:name       (name-field gene)
   :features   (associated-features gene)})

;;
;; Genetics widget
;;

(defn- reference-allele [gene]
  {:data
   (->> (:gene/reference-allele gene)
        (map :gene.reference-allele/variation)
        (map (partial pack-obj "variation")))
   :description "the reference allele of the gene"})

(defn- is-cgc? [strain]
  (some #(= (->> (:strain.location/laboratory %)
                 (:laboratory/id))
            "CGC")
        (:strain/location strain)))

(defn- strain-list [strains]
  (seq
   (map (fn [strain]
          (vassoc
           (pack-obj "strain" strain)
           :genotype (first (:strain/genotype strain))
           :transgenes (pack-obj "transgene" (first (:transgene/_strain strain)))))
        strains)))

(defn- strains [gene]
  (let [strains (:gene/strain gene)]
    {:data
     (vmap
      :carrying_gene_alone_and_cgc
      (strain-list (filter #(and (not (seq (:transgene/_strain %)))
                                 (= (count (:gene/_strain %)) 1)
                                 (is-cgc? %))
                           strains))
      
      :carrying_gene_alone
      (strain-list (filter #(and (not (seq (:transgene/_strain %)))
                                 (= (count (:gene/_strain %)) 1)
                                 (not (is-cgc? %)))
                           strains))
      
      :available_from_cgc
      (strain-list (filter #(and (or (seq (:transgene/_strain %))
                                     (not= (count (:gene/_strain %)) 1))
                                 (is-cgc? %))
                           strains))

      :others
      (strain-list (filter #(and (or (seq (:transgene/_strain %))
                                     (not= (count (:gene/_strain %)) 1))
                                 (not (is-cgc? %)))
                           strains)))

     :description
     "strains carrying this gene"}))

(defn- nonsense [change]
  (or (seq (:molecular-change/amber-uag change))
      (seq (:molecular-change/ochre-uaa change))
      (seq (:molecular-change/opal-uga change))
      (seq (:molecular-change/ochre-uaa-or-opal-uga change))
      (seq (:molecular-change/amber-uag-or-ochre-uaa change))
      (seq (:molecular-change/amber-uag-or-opal-uga change))))

(defn- process-variation [var]
 (let [cds-changes (seq (take 20 (:variation/predicted-cds var)))
       trans-changes (seq (take 20 (:variation/transcript var)))]
  (vmap
   :variation
   (pack-obj "variation" var)

   :type
   (if (:variation/transposon-insertion var)
     "transposon insertion"
     (str/join ", "
      (or 
       (those
        (if (:variation/engineered-allele var)
          "Engineered allele")
        (if (:variation/allele var)
          "Allele")
        (if (:variation/snp var)
          "SNP")
        (if (:variation/confirmed-snp var)
          "Confirmed SNP")
        (if (:variation/predicted-snp var)
          "Predicted SNP")
        (if (:variation/reference-strain-digest var)
          "RFLP"))
       ["unknown"])))

   :method_name
   (if-let [method (:variation/method var)]
     (format "<a class=\"longtext\" tip=\"%s\">%s</a>"
             (or (:method.remark/text (first (:method/remark methods))) "")
             (str/replace (:method/id method) #"_" " ")))

   :gene
   nil ;; don't populate since we're coming from gene...

   :molecular_change
   (cond
    (:variation/substitution var)
    "Substitution"
    
    (:variation/insertion var)
    "Insertion"

    (:variation/deletion var)
    "Deletion"

    (:variation/inversion var)
    "Inversion"

    (:variation/tandem-duplication var)
    "Tandem_duplication"

    :default
    "Not curated")

   :locations
   (let [changes (set (mapcat keys (concat cds-changes trans-changes)))]
     (str/join ", " (filter
                     identity
                     (map {:molecular-change/intron "Intron"
                           :molecular-change/coding-exon "Coding exon"
                           :molecular-change/utr-5 "5' UTR"
                           :molecular-change/utr-3 "3' UTR"}
                          changes))))

   :effects
   (let [changes (set (mapcat keys cds-changes))]
     (str/join ", " (set (filter
                          identity
                          (map {:molecular-change/missense "Missense"
                                :molecular-change/amber-uag "Nonsense"
                                :molecular-change/ochre-uaa "Nonsense"
                                :molecular-change/opal-uga "Nonsense"
                                :molecular-change/ochre-uaa-or-opal-uga "Nonsense"
                                :molecular-change/amber-uag-or-ochre-uaa "Nonsense"
                                :molecular-change/amber-uag-or-opal-uga "Nonsense"
                                :molecular-change/frameshift "Frameshift"
                                :molecular-change/silent "Silent"}
                               changes)))))

   :aa_change
   (str/join "<br>"
     (filter identity
       (for [cc cds-changes]
         (cond-let [n]
            (first (:molecular-change/missense cc))
            (:molecular-change.missense/text n)

            (first (nonsense cc))
            ((first (filter #(= (name %) "text") (keys n))) n)))))

   :aa_position
   (str/join "<br>"
     (filter identity
       (for [cc cds-changes]
         (if-let [n (first (:molecular-change/missense cc))]
           (:molecular-change.missense/int n)))))

   :isoform
   (seq
    (for [cc cds-changes
          :when (or (:molecular-change/missense cc)
                    (nonsense cc))]
      (pack-obj "cds" (:variation.predicted-cds/cds cc))))
          
   :phen_count
   (count (:variation/phenotype var))

   :strain
   (map #(pack-obj "strain" (:variation.strain/strain %)) (:variation/strain var)))))

(defn- alleles [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?var ...]
               :in $ ?gene
               :where [?vh :variation.gene/gene ?gene]
                      [?var :variation/gene ?vh]
                      [?var :variation/allele _]]
             db (:db/id gene))
          (map #(process-variation (entity db %))))
     :description "alleles contained in the strain"}))

(defn- polymorphisms [gene]
  (let [db (d/entity-db gene)]
    {:data
     (->> (q '[:find [?var ...]
               :in $ ?gene
               :where [?vh :variation.gene/gene ?gene]
                      [?var :variation/gene ?vh]
                      (not [?var :variation/allele _])]
             db (:db/id gene))
          (map #(process-variation (entity db %))))
     :description "polymorphisms and natural variations contained in the strain"}))

(def-rest-widget genetics [gene]
  {:name             (name-field gene)
   :reference_allele (reference-allele gene)
   :strains          (strains gene)
   :alleles          (alleles gene)
   :polymorphisms    (polymorphisms gene)})

;;
;; external_links widget
;;

(defn- xrefs [gene]
  {:data
   (reduce
    (fn [refs db]
      (update-in refs
                 [(:database/id (:gene.database/database db))
                  (:database-field/id (:gene.database/field db))
                  :ids]
                 conjv
                 (let [acc (:gene.database/accession db)]
                   (if-let [[_ rest] (re-matches #"(?:OMIM:|GI:)(.*)" acc)]
                     rest
                     acc))))
    {}
    (:gene/database gene))
   :description
   "external databases and IDs containing additional information on the object"})

(def-rest-widget external-links [gene]
  {:name  (name-field gene)
   :xrefs (xrefs gene)})
