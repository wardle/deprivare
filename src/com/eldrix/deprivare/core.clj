(ns com.eldrix.deprivare.core
  (:require [clojure.core.async :as a]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [com.eldrix.deprivare.datasets :as datasets]
            [datalevin.core :as d])
  (:import (java.io Closeable File)
           (java.time LocalDateTime)))

(def schema
  {:uk-composite-imd-2020-mysoc/UK_IMD_E_rank {:db/valueType :db.type/long}
   :uk-composite-imd-2020-mysoc/income_score {:db/valueType :db.type/double}
   :uk-composite-imd-2020-mysoc/E_expanded_decile {:db/valueType :db.type/long}
   :uk-composite-imd-2020-mysoc/UK_IMD_E_score {:db/valueType :db.type/double}
   :uk-composite-imd-2020-mysoc/overall_local_score {:db/valueType :db.type/double}
   :uk-composite-imd-2020-mysoc/original_decile {:db/valueType :db.type/long}
   :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_quintile {:db/valueType :db.type/long}
   :uk-composite-imd-2020-mysoc/employment_score {:db/valueType :db.type/double}
   :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile {:db/valueType :db.type/long}
   })

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

(defn print-available [_params]
  (pprint/print-table (map (fn [[k v]] (hash-map :id (name k) :name (:title v))) (reverse (sort-by :year datasets/available-data)))))

(defn dataset-info [params]
  (if-let [dataset (get datasets/available-data (keyword (:dataset params)))]
    (do
      (println (:title dataset))
      (println (apply str (repeat (count (:title dataset)) "-")))
      (println (:description dataset)))
    (println "Invalid :dataset parameter.\nUsage: clj -X:list to see available datasets.")))

(defn register-dataset [svc k]
  (d/transact! (.-conn svc)
               [{:installed/id   k
                 :installed/date (LocalDateTime/now)}]))

(defn fetch-installed [^Svc svc]
  (d/q '[:find [?id ...]
         :in $
         :where
         [?e :installed/id ?id]
         [?e :installed/date ?date-time]]
       (d/db (.-conn svc))))

(defn print-installed [{:keys [db]}]
  (if db
    (let [svc (open (str db))
          ids (fetch-installed svc)
          result (map #(hash-map :id (name %) :name (:title (get datasets/available-data %))) ids)]
      (pprint/print-table result))
    (println "Invalid :db parameter.\nUsage: clj -X:installed :db <database file>")))


(defn install [{:keys [db dataset]}]
  (if (and db dataset)
    (if-let [dataset' (get datasets/available-data (keyword dataset))]
      (with-open [svc (open (str db) :read-only? false)]
        (println "Installing dataset: " (:title dataset'))
        (let [ch (a/chan 16 (partition-all 1024))]
          (a/thread ((:stream-fn dataset') ch)
                    (a/close! ch))
          (loop [batch (a/<!! ch)]
            (when batch
              (d/transact! (.-conn svc) batch)
              (recur (a/<!! ch))))
          (register-dataset svc (keyword dataset))))
      (println "Invalid :dataset parameter.\nUse clj -X:list to see available datasets."))
    (println (str/join "\n"
                       ["Invalid parameters"
                        "Usage:   clj -X:install :db <database file> :dataset <dataset identifier>"
                        "  - :db      - filename of database eg. 'depriv.db'"
                        "  - :dataset - identifier of dataset eg. 'uk-composite-imd-2020-mysoc'"]))))

(defn install-all [{:keys [db]}]
  (if db
    (doseq [dataset (keys datasets/available-data)]
      (install {:db (str db) :dataset dataset}))
    (println (str/join "\n"
                       ["Invalid parameters"
                        "Usage:   clj -X:install-all :db <database file> "
                        "  - :db      - filename of database eg. 'depriv.db'"]))))

(defn fetch-lsoa [svc lsoa]
  (-> (apply merge
             (d/q '[:find [(pull ?e [*]) ...]
                    :in $ ?lsoa
                    :where
                    [?e :uk.gov.ons/lsoa ?lsoa]]
                  (d/db (.-conn svc))
                  lsoa))
      (dissoc :db/id :dataset)))

(defn fetch-max
  "Return the maximum value of the keyword specified.
  Useful if you want to calculate something special from a rank.
  For example, (fetch-max svc :uk-composite-imd-2020-mysoc/UK_IMD_E_rank)"
  [svc k]
  (d/q '[:find (max ?x) .
         :in $ ?k
         :where
         [_ ?k ?x]]
       (d/db (.-conn svc))
       k))

(comment

  (def svc (open "depriv.db"))

  (d/transact! (.-conn svc)
               [{:installed/id   :uk-composite-imd-2020-mysoc2
                 :installed/date (LocalDateTime/now)}])
  (d/q '[:find ?rank ?decile
         :in $ ?lsoa
         :where
         [?e :uk.gov.ons/lsoa ?lsoa]
         [?e :uk-composite-imd-2020-mysoc/UK_IMD_E_rank ?rank]
         [?e :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile ?decile]]
       (d/db (.-conn svc))
       "E01012672")

  (d/q '[:find (max ?rank) .
         :in $
         :where
         [_ :uk-composite-imd-2020-mysoc/UK_IMD_E_rank ?rank]]
       (d/db (.-conn svc)))

  (fetch-lsoa svc "E01012672")
  (require 'clojure.data.json)
  (clojure.data.json/write-str (fetch-lsoa svc "E01012672") :key-fn (fn [k] (str (namespace k) "-" (name k))))
  (d/q '[:find [?id ...]
         :in $
         :where
         [?e :installed/id ?id]
         [?e :installed/date ?date-time]]
       (d/db (.-conn svc)))


  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?lsoa
         :where
         [?e :uk.gov.ons/lsoa ?lsoa]]
       (d/db (.-conn svc))
       "W01000001")

  )

