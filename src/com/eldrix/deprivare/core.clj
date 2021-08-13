(ns com.eldrix.deprivare.core
  (:require [clojure.core.async :as a]
            [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clj-http.client :as client]
            [com.eldrix.deprivare.odf :as odf]
            [datalevin.core :as d])
  (:import (java.io Closeable File)
           (java.time LocalDateTime)))

(def uk-composite-imd-2020-mysoc-url
  "URL to download a composite UK score for deprivation indices for 2020 -
  based on England with adjusted scores for the other nations as per Abel, Payne
  and Barclay but calculated by Alex Parsons on behalf of MySociety."
  "https://github.com/mysociety/composite_uk_imd/blob/e7a14d3317d9462890c28513866687a3a35adc8d/uk_index/UK_IMD_E.csv?raw=true")

(def schema
  {})

(deftype Svc [conn]
  Closeable
  (close [_] (d/close conn)))

(defn open [dir & {:keys [read-only?] :or {read-only? true}}]
  (if (and read-only? (not (.exists (File. dir))))
    (do (println "Error: :db specified does not exist")
        (System/exit 1))
    (->Svc (d/create-conn dir schema))))

(defn close [^Svc svc]
  (d/close (.-conn svc)))

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
                    (map #(assoc % :uk.gov.ons/lsoa (:uk-composite-imd-2020-mysoc/lsoa %)
                                   :dataset :uk-composite-imd-2020-mysoc))
                    (map #(dissoc % :uk-composite-imd-2020-mysoc/lsoa))
                    (map #(a/>!! ch %))))))))

(defn stream-wales-imd-2019-ranks [ch]
  (let [f (download-file "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"
                         "wimd-2019-" ".ods")
        data (odf/sheet-data f "WIMD_2019_ranks"
                             :headings (map #(keyword "wales-imd-2019" (name %)) [:lsoa :lsoa-name :authority-name :wimd_2019 :income :employment :health :education :access_to_services :housing :community_safety :physical_environment])
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
                             :headings (map #(keyword "wales-imd-2019" (name %)) [:lsoa :lsoa-name :authority-name :wimd_2019 :wimd_2019_decile :wimd_2019_quintile :wimd_2019_quartile])
                             :pred (fn [row] (and (= (count row) 7) (.startsWith (first row) "W"))))]
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
                                 :stream-fn   stream-uk-composite-imd-2020}
   :wales-imd-2019-ranks        {:title       "Welsh Index of Deprivation - ranks, 2019"
                                 :year        2019
                                 :description "Welsh Index of Deprivation - raw ranks for each domain, by LSOA."
                                 :stream-fn   stream-wales-imd-2019-ranks}
   :wales-imd-2019-quantiles    {:title       "Welsh Index of Deprivation - quantiles, 2019"
                                 :year        2019
                                 :description "Welsh Index of Deprivation - with composite rank with decile, quintile and quartile."
                                 :stream-fn   stream-wales-imd-2019-quantiles}})

(defn print-available [_params]
  (pprint/print-table (map (fn [[k v]] (hash-map :id (name k) :name (:title v))) (reverse (sort-by :year available-data)))))

(defn dataset-info [params]
  (if-let [dataset (get available-data (keyword (:dataset params)))]
    (do
      (println (:title dataset))
      (println (apply str (repeat (count (:title dataset)) "-")))
      (println (:description dataset)))
    (println "Invalid :dataset parameter.\nUsage: clj -X:list to see available datasets.")))

(defn register-dataset [svc k]
  (d/transact! (.-conn svc)
               [{:installed/id   k
                 :installed/date (LocalDateTime/now)}]))

(defn print-installed [{:keys [db]}]
  (if db
    (let [svc (open (str db))
          ids (d/q '[:find [?id ...]
                     :in $
                     :where
                     [?e :installed/id ?id]
                     [?e :installed/date ?date-time]]
                   (d/db (.-conn svc)))
          result (map #(hash-map :id (name %) :name (:title (get available-data %))) ids)]
      (pprint/print-table result))
    (println "Invalid :db parameter.\nUsage: clj -X:installed :db <database file>")))

(defn install [{:keys [db dataset]}]
  (if (and db dataset)
    (if-let [dataset' (get available-data (keyword dataset))]
      (with-open [svc (open (str db) :read-only? false)]
        (println "Installing dataset: " (:title dataset'))
        (let [ch (a/chan 16 (partition-all 1024))]
          (a/thread ((:stream-fn dataset') ch)
                    (a/close! ch))
          (loop [batch (a/<!! ch)]
            (when batch
              (d/transact! (.-conn svc) batch)
              (recur (a/<!! ch))))
          (register-dataset svc (keyword dataset))
          (println "Import complete")))
      (println "Invalid :dataset parameter.\nUse clj -X:list to see available datasets."))
    (println (str/join "\n"
                       ["Invalid parameters"
                        "Usage:   clj -X:install :db <database file> :dataset <dataset identifier>"
                        "  - :db      - filename of database eg. 'depriv.db'"
                        "  - :dataset - identifier of dataset eg. 'uk-composite-imd-2020-mysoc'"]))))

(defn fetch-lsoa [svc lsoa]
  (-> (apply merge
             (d/q '[:find [(pull ?e [*]) ...]
                    :in $ ?lsoa
                    :where
                    [?e :uk.gov.ons/lsoa ?lsoa]]
                  (d/db (.-conn svc))
                  lsoa))
      (dissoc :db/id :dataset)))

(comment
  (def reader (io/reader uk-composite-imd-2020-mysoc-url))
  (def lines (csv/read-csv reader))
  (first lines)
  (second lines)
  (take 5 lines)
  (take 5 (map parse-uk-composite-2020-mysoc (rest lines)))
  (d/transact! (.-conn svc) (map (fn [row]
                                   (parse-uk-composite-2020-mysoc row)) (rest lines)))
  (d/transact! (.-conn svc) [{:lsoa "E01012672" :wibble 3}])
  (def svc (open "depriv.db"))
  (download-uk-composite-imd-2020 svc)

  (d/transact! (.-conn svc)
               [{:installed/id   :uk-composite-imd-2020-mysoc2
                 :installed/date (LocalDateTime/now)}])
  (d/q '[:find ?rank ?decile
         :in $ ?lsoa
         :where
         [?e :lsoa ?lsoa]
         [?e :uk-composite-imd-2020-mysoc/UK_IMD_E_rank ?rank]
         [?e :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile ?decile]]
       (d/db (.-conn svc))
       "E01012672")
  (fetch-lsoa svc "E01012672")
  (require 'clojure.data.json)
  (clojure.data.json/write-str (fetch-lsoa svc "E01012672") :key-fn (fn [k] (str (namespace k) "-" (name k))))
  (d/q '[:find [?id ...]
         :in $
         :where
         [?e :installed/id ?id]
         [?e :installed/date ?date-time]]
       (d/db (.-conn svc)))



  (def ch (a/chan 16 (partition-all 5)))
  (a/thread (stream-wales-imd-2019-ranks ch))
  (a/<!! ch)

  (def ch (a/chan 16 (partition-all 5)))
  (a/thread (stream-uk-composite-imd-2020 ch))
  (a/<!! ch)
  (d/transact! (.-conn svc) (a/<!! ch))
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?lsoa
         :where
         [?e :uk.gov.ons/lsoa ?lsoa]]
       (d/db (.-conn svc))
       "W01000001")

  )

