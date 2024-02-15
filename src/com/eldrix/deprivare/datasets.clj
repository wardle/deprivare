(ns com.eldrix.deprivare.datasets
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [com.eldrix.deprivare.odf :as odf]
            [clojure.data.csv :as csv]
            [hato.client :as hc])
  (:import (java.io File)))


;;
;; dataset specification
;;

(s/def ::id keyword?)
(s/def ::table string?)
(s/def ::title string?)
(s/def ::year int?)
(s/def ::description string?)
(s/def ::properties (s/coll-of string?))                    ;; a list of properties that can be generated from this dataset
(s/def ::stream-fn fn?)                                     ;; function to stream data for a dataset on a channel
(s/def ::create-sql string?)                                ;; SQL to prepare a statement for creation of database table
(s/def ::insert-sql string?)                                ;; SQL to prepare a statement for insertion of row(s)
(s/def ::insert-data (s/or :coll (s/coll-of ifn?) :fn ifn?)) ;; a fn or collection of keywords or functions that will be used with `juxt` to get data for insertion per row
(s/def ::dataset (s/keys :req-un [::id ::table ::title ::year ::description ::stream-fn ::create-sql ::insert-sql ::insert-data]))


;;
;;
;;

(defn- download-file
  "Downloads a file from a URL to a temporary file, which is returned.
  Sets the user agent header appropriately; some URLs return a 403 if there is
  no defined user agent, including gov.wales."
  [url prefix suffix]
  (let [f (File/createTempFile prefix suffix)]
    (with-open [is (:body (hc/get url {:headers     {"User-Agent" "deprivare v1.0"}
                                       :as          :stream
                                       :http-client {:redirect-policy :always}}))
                os (io/output-stream f)]
      (io/copy is os)
      f)))


(defn stream-csv-with-headings
  "Streams a CSV file from the download-url. First row of the CSV file should
  have headings and will be skipped. If `headers` are specified, then they will
  be checked."
  ([dataset ch]
   (stream-csv-with-headings dataset ch true))
  ([{:keys [download-url headers]} ch close?]
   (with-open [reader (io/reader download-url)]
     (let [lines (csv/read-csv reader)
           headings (map keyword (first lines))
           data (rest lines)]
       (if (and headers (not= headers (first lines)))
         (throw (ex-info "invalid CSV headers" {:expected headers :actual (first lines)}))
         (async/<!! (async/onto-chan!! ch (map zipmap (repeat headings) data) close?)))))))

(defn stream-wales-imd-2019-ranks
  [{:keys [download-url]} ch]
  (let [f (download-file download-url "wimd-2019-ranks" ".ods")
        data (odf/sheet-data f "WIMD_2019_ranks"
                             :headings [:lsoa :lsoa_name :authority_name :wimd_2019 :income :employment :health :education :access_to_services :housing :community_safety :physical_environment]
                             :pred #(and (= (count %) 12) (.startsWith (first %) "W")))]
    (async/<!! (async/onto-chan!! ch data))))

(defn stream-wales-imd-2019-quantiles
  [{:keys [download-url]} ch]
  (let [f (download-file download-url "wimd-2019-quantiles" ".ods")
        data (odf/sheet-data f "Deciles_quintiles_quartiles"
                             :headings [:lsoa :lsoa_name :authority_name :wimd_2019 :wimd_2019_decile :wimd_2019_quintile :wimd_2019_quartile]
                             :pred (fn [row] (and (= (count row) 7) (.startsWith ^String (first row) "W"))))]
    (async/<!! (async/onto-chan!! ch data))))

(defn stream-england-imd-2019-ranks
  [{:keys [download-url]} ch]
  (let [load-workbook-from-file (requiring-resolve 'dk.ative.docjure.spreadsheet/load-workbook-from-file)
        select-sheet (requiring-resolve 'dk.ative.docjure.spreadsheet/select-sheet)
        select-columns (requiring-resolve 'dk.ative.docjure.spreadsheet/select-columns)
        f (download-file download-url "eimd-2019-ranks" ".xlsx")
        data (rest (->> (load-workbook-from-file f)
                        (select-sheet "IMD2019")
                        (select-columns {:A :lsoa
                                         :B :lsoa_name
                                         :C :authority_code
                                         :D :authority_name
                                         :E :eimd_2019_rank
                                         :F :eimd_2019_decile})))]
    (async/<!! (async/onto-chan!! ch data))))


;;
;;
;;
;;
;;
;;

(def datasets
  [{:id           :uk-composite-imd-2020-mysoc
    :table        "uk_composite_imd_2020_mysoc"
    :title        "UK composite index of multiple deprivation, 2020 (MySociety)"
    :year         2020
    :description  (str/join "\n"
                            ["A composite UK score for deprivation indices for 2020 - based on England"
                             "with adjusted scores for the other nations as per Abel, Payne and Barclay but"
                             "calculated by Alex Parsons on behalf of MySociety."])
    :docstring    (str/join "\n"
                            ["Returns a composite rank, decile and quintile and also dynamically generates a"
                             "quartile based on rank."])
    :url          "https://pages.mysociety.org/composite_uk_imd/datasets/uk_index/2_0_0"
    :download-url "https://pages.mysociety.org/composite_uk_imd/data/uk_index/latest/UK_IMD_E.csv"
    :headers      ["nation" "lsoa" "overall_local_score" "income_score" "employment_score"
                   "UK_IMD_E_score" "original_decile" "E_expanded_decile" "UK_IMD_E_rank"
                   "UK_IMD_E_pop_decile" "UK_IMD_E_pop_quintile"]
    :stream-fn    stream-csv-with-headings
    :create-sql   "create table if not exists uk_composite_imd_2020_mysoc
                   (lsoa text primary key, UK_IMD_E_pop_decile integer, UK_IMD_E_pop_quintile integer,
                   UK_IMD_E_rank integer, UK_IMD_E_score integer)"
    :insert-sql   "insert or replace into uk_composite_imd_2020_mysoc (lsoa, UK_IMD_E_pop_decile, UK_IMD_E_pop_quintile, UK_IMD_E_rank, UK_IMD_E_score) values (?,?,?,?,?)"
    :insert-data  [:lsoa :UK_IMD_E_pop_decile :UK_IMD_E_pop_quintile :UK_IMD_E_rank :UK_IMD_E_score]
    :index-sql    "create index if not exists uk_composite_imd_2020_mysoc_rank_idx on uk_composite_imd_2020_mysoc(uk_imd_e_rank)"
    :fetch-sql    "select * from uk_composite_imd_2020_mysoc a left join (select lsoa,ntile(4) over(order by UK_IMD_E_rank) as UK_IMD_E_pop_quartile from uk_composite_imd_2020_mysoc) b on a.lsoa = b.lsoa where a.lsoa=?"
    :properties   ["lsoa" "UK_IMD_E_pop_decile" "UK_IMD_E_pop_quintile" "UK_IMD_E_pop_quartile" "UK_IMD_E_rank" "UK_IMD_E_score"]}
   {:id           :wales-imd-2019-ranks
    :table        "wales_imd_2019_ranks"
    :title        "Welsh Index of Deprivation - ranks, 2019"
    :year         2019
    :description  "Welsh Index of Deprivation - raw ranks for each domain, by LSOA."
    :download-url "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"
    :stream-fn    stream-wales-imd-2019-ranks
    :create-sql   "create table if not exists wales_imd_2019_ranks
                 (lsoa text primary key, lsoa_name text, authority_name text, access_to_services integer, community_safety integer,
                  education integer, employment integer, health integer, housing integer, income integer, physical_environment integer, wimd_2019 integer)"
    :insert-sql   "insert or replace into wales_imd_2019_ranks (lsoa, lsoa_name, authority_name, access_to_services, community_safety, education, employment, health, housing, income, physical_environment, wimd_2019) values (?,?,?,?,?,?,?,?,?,?,?,?)"
    :insert-data  [:lsoa :lsoa_name :authority_name :access_to_services :community_safety :education :employment :health :housing :income :physical_environment :wimd_2019]
    :fetch-sql    "select * from wales_imd_2019_ranks where lsoa=?"}

   {:id           :wales-imd-2019-quantiles
    :table        "wales_imd_2019_quantiles"
    :title        "Welsh Index of Deprivation - quantiles, 2019"
    :year         2019
    :description  "Welsh Index of Deprivation - with composite rank with decile, quintile and quartile."
    :download-url "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"
    :stream-fn    stream-wales-imd-2019-quantiles
    :create-sql   "create table if not exists wales_imd_2019_quantiles
                   (lsoa text primary key, lsoa_name text, authority_name text, wimd_2019 integer, wimd_2019_decile integer, wimd_2019_quintile integer, wimd_2019_quartile integer)"
    :insert-sql   "insert or replace into wales_imd_2019_quantiles (lsoa, lsoa_name, authority_name, wimd_2019, wimd_2019_decile, wimd_2019_quintile, wimd_2019_quartile) values (?,?,?,?,?,?,?)"
    :insert-data  [:lsoa :lsoa_name :authority_name :wimd_2019 :wimd_2019_decile :wimd_2019_quintile :wimd_2019_quartile]
    :fetch-sql    "select * from wales_imd_2019_quantiles where lsoa=?"}

   {:id           :england-imd-2019-ranks
    :table        "england_imd_2019_ranks"
    :title        "England Index of Deprivation - ranks, 2019"
    :year         2019
    :url          "https://www.gov.uk/government/statistics/english-indices-of-deprivation-2019"
    :download-url "https://assets.publishing.service.gov.uk/media/5d8b3abded915d0373d3540f/File_1_-_IMD2019_Index_of_Multiple_Deprivation.xlsx"
    :stream-fn    stream-england-imd-2019-ranks
    :description  "Statistics on relative deprivation in small areas in England."
    :create-sql   "create table if not exists england_imd_2019_ranks (lsoa text primary key, lsoa_name text, authority_code text, authority_name text, eimd_2019_rank integer, eimd_2019_decile integer)"
    :insert-sql   "insert or replace into england_imd_2019_ranks (lsoa, lsoa_name, authority_code, authority_name, eimd_2019_rank, eimd_2019_decile) values (?,?,?,?,?,?)"
    :insert-data  [:lsoa :lsoa_name :authority_code :authority_name :eimd_2019_rank :eimd_2019_decile]
    :fetch-sql    "select * from england_imd_2019_ranks where lsoa=?"}])


;;
;;
;;

(def dataset-by-id
  "Return a dataset given an dataset-id."
  (reduce (fn [acc {:keys [id] :as dataset}]
            (assoc acc id dataset)) {} datasets))

(def ^:deprecated available-data
  "DEPRECATED: use `dataset-by-id` instead"
  dataset-by-id)

(def dataset-by-table-name
  (reduce (fn [acc {:keys [table] :as dataset}]
            (assoc acc table dataset)) {} datasets))

(s/fdef properties*
  :args (s/cat :dataset ::dataset))
(defn properties*
  "Return the properties that are available from this dataset."
  [{:keys [id insert-data properties] :as dataset}]
  (when (and (empty? insert-data) (empty? properties))
    (throw (ex-info "missing properties for dataset and cannot derive from ':data' specification" dataset)))
  (map #(keyword (name id) (name %)) (or properties insert-data)))

(s/fdef properties
  :args (s/cat :datasets (s/coll-of ::dataset)))
(defn properties
  "Return the properties that can be provided from the collection of datasets."
  [datasets]
  (apply set/union (map properties* datasets)))

(defn ^:deprecated properties-for-dataset-id
  "DEPRECATED: use `properties*` instead."
  [dataset-id]
  (when-let [dataset (get dataset-by-id (keyword (name dataset-id)))]
    (properties* dataset)))

(defn ^:deprecated properties-for-dataset-ids
  "DEPRECATED: use `properties` instead."
  [dataset-ids]
  (apply set/union (map properties-for-dataset-id dataset-ids)))

(comment
  (def ch (async/chan))
  (async/thread (stream-england-imd-2019-ranks (dataset-by-id :england-imd-2019-ranks) ch))
  (async/<!! ch))

