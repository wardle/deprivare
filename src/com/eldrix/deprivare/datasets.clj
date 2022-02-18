(ns com.eldrix.deprivare.datasets
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.async :as a]
            [com.eldrix.deprivare.odf :as odf]
            [clojure.data.csv :as csv]
            [clojure.edn :as edn])
  (:import [java.io File]))

(defn parse-double-as-long [s] (long (parse-double s)))
(def property-parsers
  {:uk-composite-imd-2020-mysoc/income_score          parse-double
   :uk-composite-imd-2020-mysoc/E_expanded_decile     parse-double-as-long
   :uk-composite-imd-2020-mysoc/UK_IMD_E_score        parse-double
   :uk-composite-imd-2020-mysoc/overall_local_score   parse-double
   :uk-composite-imd-2020-mysoc/original_decile       parse-long
   :uk-composite-imd-2020-mysoc/UK_IMD_E_rank         parse-double-as-long
   :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_quintile parse-long
   :uk-composite-imd-2020-mysoc/employment_score      parse-double
   :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile   parse-long})

(defn parse
  [m]
  (reduce-kv (fn [acc k v]
               (if-let [parser (get property-parsers k)]
                 (assoc acc k (try (parser v) (catch Exception e
                                                (throw (ex-info "failed to parse" {:k k :v v})))))
                 (assoc acc k v))) {} m))

(defn- download-file
  "Downloads a file from a URL to a temporary file, which is returned.
  Sets the user agent header appropriately; some URLs return a 403 if there is
  no defined user agent, including gov.wales."
  [url prefix suffix]
  (let [f (File/createTempFile prefix suffix)]
    (with-open [is (:body (client/get url {:headers {"User-Agent" "deprivare v0.1"} :as :stream}))
                os (io/output-stream f)]
      (io/copy is os)
      f)))

(def uk-composite-imd-2020-mysoc-url
  "URL to download a composite UK score for deprivation indices for 2020 -
  based on England with adjusted scores for the other nations as per Abel, Payne
  and Barclay but calculated by Alex Parsons on behalf of MySociety."
  "https://github.com/mysociety/composite_uk_imd/blob/e7a14d3317d9462890c28513866687a3a35adc8d/uk_index/UK_IMD_E.csv?raw=true")

(def headers-uk-composite-2020-mysoc
  ["nation"
   "lsoa"
   "overall_local_score"
   "income_score"
   "employment_score"
   "UK_IMD_E_score"
   "original_decile"
   "E_expanded_decile"
   "UK_IMD_E_rank"
   "UK_IMD_E_pop_decile"
   "UK_IMD_E_pop_quintile"])

(defn stream-uk-composite-imd-2020
  "Streams the uk-composite-imd-2020-mysoc data to the channel specified."
  [ch]
  (with-open [reader (io/reader uk-composite-imd-2020-mysoc-url)]
    (let [lines (csv/read-csv reader)]
      (if-not (= headers-uk-composite-2020-mysoc (first lines))
        (throw (ex-info "invalid CSV headers" {:expected headers-uk-composite-2020-mysoc :actual (first lines)}))
        (doall (->> (map zipmap (->> (first lines)
                                     (map #(keyword "uk-composite-imd-2020-mysoc" %))
                                     repeat)
                         (rest lines))
                    (map parse)
                    (map #(assoc % :uk.gov.ons/lsoa (:uk-composite-imd-2020-mysoc/lsoa %)
                                   :dataset :uk-composite-imd-2020-mysoc))
                    (map #(dissoc % :uk-composite-imd-2020-mysoc/lsoa))
                    (map #(a/>!! ch %))))))))

(defn stream-wales-imd-2019-ranks [ch]
  (let [f (download-file "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"
                         "wimd-2019-" ".ods")
        data (odf/sheet-data f "WIMD_2019_ranks"
                             :headings (map #(keyword "wales-imd-2019" (name %))
                                            [:lsoa :lsoa_name :authority_name :wimd_2019 :income :employment :health :education :access_to_services :housing :community_safety :physical_environment])
                             :pred #(and (= (count %) 12) (.startsWith (first %) "W")))]
    (doall (->> data
                (map #(assoc % :uk.gov.ons/lsoa (:wales-imd-2019/lsoa %)
                               :dataset :wales-imd-2019-ranks))
                (map #(dissoc % :wales-imd-2019/lsoa))
                (map #(a/>!! ch %))))))

(defn stream-wales-imd-2019-quantiles [ch]
  (let [f (download-file "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"
                         "wimd-2019-" ".ods")
        data (odf/sheet-data f "Deciles_quintiles_quartiles"
                             :headings (map #(keyword "wales-imd-2019" (name %))
                                            [:lsoa :lsoa_name :authority_name :wimd_2019 :wimd_2019_decile :wimd_2019_quintile :wimd_2019_quartile])
                             :pred (fn [row] (and (= (count row) 7) (.startsWith ^String (first row) "W"))))]
    (doall (->> data
                (map #(assoc % :uk.gov.ons/lsoa (:wales-imd-2019/lsoa %)
                               :dataset :wales-imd-2019-quantiles))
                (map #(dissoc % :wales-imd-2019/lsoa))
                (map #(a/>!! ch %))))))





(def available-data
  {:uk-composite-imd-2020-mysoc {:title       "UK composite index of multiple deprivation, 2020 (MySociety)"
                                 :year        2020
                                 :description (str/join "\n" ["A composite UK score for deprivation indices for 2020 - based on England"
                                                              "with adjusted scores for the other nations as per Abel, Payne and Barclay but"
                                                              "calculated by Alex Parsons on behalf of MySociety."])
                                 :properties  ["E_expanded_decile" "UK_IMD_E_pop_decile" "UK_IMD_E_pop_quintile" "UK_IMD_E_rank" "UK_IMD_E_score" "employment_score" "income_score" "nation" "original_decile" "overall_local_score"]
                                 :stream-fn   stream-uk-composite-imd-2020}
   :wales-imd-2019-ranks        {:title       "Welsh Index of Deprivation - ranks, 2019"
                                 :year        2019
                                 :description "Welsh Index of Deprivation - raw ranks for each domain, by LSOA."
                                 :namespace   "wales-imd-2019"
                                 :properties  ["lsoa_name" "authority_name" "access_to_services" "community_safety" "education" "employment" "health" "housing"
                                               "income" "physical_environment" "wimd_2019"]
                                 :stream-fn   stream-wales-imd-2019-ranks}
   :wales-imd-2019-quantiles    {:title       "Welsh Index of Deprivation - quantiles, 2019"
                                 :year        2019
                                 :description "Welsh Index of Deprivation - with composite rank with decile, quintile and quartile."
                                 :namespace   "wales-imd-2019"
                                 :properties  ["lsoa_name" "authority_name" "wimd_2019" "wimd_2019_decile" "wimd_2019_quintile" "wimd_2019_quartile"]
                                 :stream-fn   stream-wales-imd-2019-quantiles}})


(defn properties-for-dataset [dataset]
  (let [dataset' (get available-data (keyword dataset))
        nspace (or (:namespace dataset') (name dataset))]
    (set (map #(keyword nspace (name %))
              (:properties dataset')))))

(defn properties-for-datasets [datasets]
  (apply set/union (map properties-for-dataset datasets)))

(comment

  (properties-for-dataset :uk-composite-imd-2020-mysoc)
  (properties-for-datasets [:uk-composite-imd-2020-mysoc :wales-imd-2019-quantiles])
  (def ch (a/chan 16 (partition-all 5)))
  (a/thread (stream-wales-imd-2019-ranks ch))
  (a/<!! ch)

  (def ch (a/chan 16 (partition-all 5)))
  (a/thread (stream-uk-composite-imd-2020 ch))
  (a/<!! ch))