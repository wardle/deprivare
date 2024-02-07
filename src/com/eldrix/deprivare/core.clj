(ns com.eldrix.deprivare.core
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [com.eldrix.deprivare.datasets :as datasets]
            [com.eldrix.deprivare.store :as store])
  (:import (java.io Closeable)
           (java.sql Connection)))

(deftype Svc [^Connection conn fetch-lsoa-fn]
  Closeable
  (close [_] (.close conn)))

(defn open
  "Open a deprivare index from the filename specified."
  [filename & {:keys [read-only?] :or {read-only? true}}]
  (when (and read-only? (not (.exists (io/file filename))))
    (throw (ex-info (str "index not found: " filename) {})))
  (let [conn (store/open-connection filename)
        fetch-lsoa (store/make-fetch-lsoa conn)]
    (store/check-version! conn read-only?)
    (->Svc conn fetch-lsoa)))

(defn close [^Svc svc]
  (.close ^Connection (.-conn svc)))

(defn print-available [_]
  (pprint/print-table
    (map (fn [[k v]] (hash-map :id (name k) :name (:title v)))
         (reverse (sort-by :year datasets/dataset-by-id)))))

(defn fetch-lsoa [^Svc svc lsoa]
  ((.-fetch-lsoa-fn svc) lsoa))

(defn available-properties [^Svc svc]
  (store/available-properties (.-conn svc)))

(defn fetch-installed [^Svc svc]
  (store/fetch-installed-datasets (.-conn svc)))


;; **************************************************************************
;;
;; Command-line interface
;;
;; These functions are designed to be executed from the command-line using
;; clojure -X:xxxx .. ..
;;
;; **************************************************************************

(defn dataset-info
  "Return information about the named dataset.
  Parameters:
  - dataset : a string version of the dataset identifier"
  [{:keys [dataset]}]
  (if-let [dataset (get datasets/dataset-by-id (keyword dataset))]
    (do
      (println (:title dataset))
      (println (apply str (repeat (count (:title dataset)) "-")))
      (println (:description dataset)))
    (println "Invalid :dataset parameter.\nUsage: clj -X:list to see available datasets.")))

(defn print-installed
  [{:keys [db]}]
  (if db
    (let [svc (open (str db))
          ids (map :id (fetch-installed svc))
          result (map #(hash-map :id (name %) :name (:title (get datasets/dataset-by-id %))) ids)]
      (pprint/print-table result))
    (println "Invalid :db parameter.\nUsage: clj -X:installed :db <database file>")))

(defn install
  [{:keys [db dataset]}]
  (if (and db dataset)
    (if-let [dataset' (get datasets/dataset-by-id (keyword dataset))]
      (with-open [svc (open (str db) :read-only? false)]
        (println "Installing dataset: " (:title dataset'))
        (let [ch (a/chan 16 (partition-all 1048576))]
          (store/import-dataset (.-conn svc) ch dataset')))
      (println "Invalid :dataset parameter.\nUse clj -X:list to see available datasets."))
    (println (str/join "\n"
                       ["Invalid parameters"
                        "Usage:   clj -X:install :db <database file> :dataset <dataset identifier>"
                        "  - :db      - filename of database eg. 'depriv.db'"
                        "  - :dataset - identifier of dataset eg. 'uk-composite-imd-2020-mysoc'"]))))

(defn install-all
  [{:keys [db]}]
  (if db
    (doseq [dataset-id (map :id datasets/datasets)]
      (install {:db (str db) :dataset dataset-id}))
    (println (str/join "\n"
                       ["Invalid parameters"
                        "Usage:   clj -X:install-all :db <database file> "
                        "  - :db      - filename of database eg. 'depriv.db'"]))))

(comment
  (def svc (open "depriv2.db"))
  (fetch-lsoa svc "E01012672")
  (fetch-lsoa svc "W01001552")
  (require 'clojure.data.json)
  (clojure.data.json/write-str (fetch-lsoa svc "E01012672") :key-fn (fn [k] (str (namespace k) "-" (name k)))))



