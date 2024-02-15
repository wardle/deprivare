(ns com.eldrix.deprivare.store
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [com.eldrix.deprivare.datasets :as datasets]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.sql Connection)))

(def version 1)

(defn get-user-version
  [conn]
  (:user_version (jdbc/execute-one! conn ["SELECT * from pragma_user_version"] {:builder-fn rs/as-unqualified-maps})))

(defn set-user-version!
  [conn v]
  (jdbc/execute-one! conn [(str "PRAGMA user_version(" v ")")]))

(defn check-version!
  [conn read-only]
  (let [v (get-user-version conn)]
    (cond
      ;; if the current user-version is 0, then the database is empty and newly created
      (and (= v 0) (not read-only))
      (set-user-version! conn version)
      (= v 0)
      (throw (ex-info "uninitialised index" {}))
      (not= v version)
      (throw (ex-info "incompatible index version" {:expected version :actual v})))))

(defn import-dataset
  "Import a dataset into the database `conn` from the channel `ch`."
  [conn ch {:keys [stream-fn create-sql insert-sql insert-data index-sql], :as dataset}]
  (when-not (s/valid? ::datasets/dataset dataset)
    (throw (ex-info "invalid dataset specification" (s/explain-data ::datasets/dataset dataset))))
  (async/thread (stream-fn dataset ch))
  (log/info "started import of dataset" (select-keys dataset [:id :title]))
  (jdbc/execute-one! conn [create-sql])
  (let [data-fn (if (fn? insert-data) insert-data (apply juxt insert-data))]
    (loop [batch (async/<!! ch)]
      (when batch
        (jdbc/with-transaction [txn conn]
          (jdbc/execute-batch! txn insert-sql (map data-fn batch) {}))
        (recur (async/<!! ch)))))
  (when index-sql (jdbc/execute-one! conn [index-sql]))
  (jdbc/execute-one! conn ["vacuum"])
  (log/info "finished import of dataset" (select-keys dataset [:id :title])))

(defn fetch-installed-datasets
  "Return a set of installed datasets."
  [conn]
  (into #{}
        (map #(-> % :name datasets/dataset-by-table-name))
        (jdbc/plan conn ["SELECT name FROM sqlite_schema WHERE type ='table' AND name NOT LIKE 'sqlite_%'"])))

(defn available-properties
  [conn]
  (datasets/properties (fetch-installed-datasets conn)))

(defn do-fetch-lsoa
  [conn {:keys [id fetch-sql]} lsoa]
  (let [nspace (name id)]
    (update-keys (jdbc/execute-one! conn [fetch-sql lsoa] {:builder-fn rs/as-unqualified-maps})
                 #(keyword nspace (name %)))))

(defn do-fetch-lsoa
  [conn {:keys [id fetch-sql]} lsoa]
  (let [nspace (name id)]
    (update-keys (jdbc/execute-one! conn [fetch-sql lsoa] {:builder-fn rs/as-unqualified-maps})
                 #(keyword nspace (name %)))))

(defn make-fetch-lsoa
  [conn]
  (let [datasets (fetch-installed-datasets conn)]
    (fn [lsoa]
      (apply merge (map #(do-fetch-lsoa conn % lsoa) datasets)))))

(defn open-connection
  ^Connection [filename]
  (jdbc/get-connection (str "jdbc:sqlite:" filename)))


(comment
  (def conn (open-connection "depriv2.db"))
  (def ch (async/chan 1 (partition-all 5000)))
  (def dataset (:uk-composite-imd-2020-mysoc datasets/dataset-by-id))
  (def dataset (datasets/dataset-by-id :wales-imd-2019-ranks))
  (s/valid? ::datasets/dataset dataset)
  (s/explain-data ::datasets/dataset dataset)
  (import-dataset conn ch dataset)
  (async/thread ((:stream-fn dataset) ch))
  (async/<!! ch)
  (def fetch-lsoa (make-fetch-lsoa conn))
  (fetch-lsoa " "))




