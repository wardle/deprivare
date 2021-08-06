(ns com.eldrix.uk-depriv.core
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [datalevin.core :as d])
  (:import (java.io Closeable)
           (java.time LocalDateTime)))


(def uk-composite-imd-2020-mysoc-url
  "URL to download a composite UK score for deprivation indices for 2020 -
  based on England with adjusted scores for the other nations as per Abel, Payne
  and Barclay but calculated by Alex Parsons on behalf of MySociety."
  "https://github.com/mysociety/composite_uk_imd/blob/e7a14d3317d9462890c28513866687a3a35adc8d/uk_index/UK_IMD_E.csv?raw=true")

(def schema
  {:lsoa                                                           {:db/valueType :db.type/string}
   :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_rank       {:db/valueType :db.type/double}
   :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_pop_decile {:db/valueType :db.type/long}
   })

(deftype Svc [conn]
  Closeable
  (close [_] (d/close conn)))

(defn open [dir]
  (->Svc (d/create-conn dir schema)))

(defn close [^Svc svc]
  (d/close (.-conn svc)))

(defn parse-uk-composite-2020-mysoc [row]
  {:lsoa                                                           (get row 1)
   :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_rank       (clojure.edn/read-string (get row 8))
   :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_pop_decile (clojure.edn/read-string (get row 9))})

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

(defn download-uk-composite-imd-2020
  "Downloads and installs the uk-composite-imd-2020-mysoc data."
  [^Svc svc]
  (with-open [reader (io/reader uk-composite-imd-2020-mysoc-url)]
    (let [lines (csv/read-csv reader)]
      (if-not (= headers-uk-composite-2020-mysoc (first lines))
        (throw (ex-info "invalid CSV headers" {:expected headers-uk-composite-2020-mysoc :actual (first lines)}))
        (d/transact! (.-conn svc) (map (fn [row]
                                         (parse-uk-composite-2020-mysoc row)) (rest lines)))))))

(def available-data
  {:uk-composite-imd-2020-mysoc
   {:title       "UK composite index of multiple deprivation, 2020 (MySociety)."
    :year        2020
    :description "A composite UK score for deprivation indices for 2020 -
  based on England with adjusted scores for the other nations as per Abel, Payne
  and Barclay but calculated by Alex Parsons on behalf of MySociety."
    :download-fn download-uk-composite-imd-2020}})

(comment
  (def reader (io/reader uk-composite-imd-2020-mysoc-url))
  (def lines (csv/read-csv reader))
  (first lines)
  (second lines)
  (take 5 lines)
  (take 5 (map parse-uk-composite-2020-mysoc (rest lines)))
  (d/transact! (.-conn svc) (map (fn [row]
                                   (parse-uk-composite-2020-mysoc row)) (rest lines)))

  (def svc (open "depriv.db"))
  (download-uk-composite-imd-2020 svc)

  (d/transact! (.-conn svc)
               [{:installed/id   :uk-composite-imd-2020-mysoc2
                 :installed/date (LocalDateTime/now)}])
  (d/q '[:find ?rank ?decile
         :in $ ?lsoa
         :where
         [?e :lsoa ?lsoa]
         [?e :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_rank ?rank]
         [?e :com.github.mysociety.composite_uk_imd.2020/UK_IMD_E_pop_decile ?decile]]
       (d/db (.-conn svc))
       "E01012672")
  (d/q '[:find ?id ?date-time
         :in $
         :where
         [?e :installed/id ?id]
         [?e :installed/date ?date-time]]
       (d/db (.-conn svc)))
  )