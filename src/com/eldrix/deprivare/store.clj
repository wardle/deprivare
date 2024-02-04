(ns com.eldrix.deprivare.store
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [com.eldrix.deprivare.datasets :as datasets]
            [next.jdbc :as jdbc]))

(defn create-store
  [filename]
  (if (.exists (io/file filename))
    (throw (ex-info (str "database already exists: " filename) {}))
    (jdbc/get-connection (str "jdbc:sqlite:" filename))))

(defn open-store [filename]
  (if (.exists (io/file filename))
      (jdbc/get-connection (str "jdbc:sqlite:" filename))
      (throw (ex-info (str "database not found: " filename) {}))))


(comment
  (def conn (open-store "depriv2.db"))
  (require '[com.eldrix.deprivare.datasets :as datasets])
  (def ch (async/chan 1 (partition-all 5000)))

  (let [{:keys [id title stream-fn create-sql insert-sql data-fn], :as dataset}
        (:uk-composite-imd-2020-mysoc datasets/available-data)]
    (when-not stream-fn
      (throw (ex-info "no stream function defined for dataset" dataset)))
    (async/thread (stream-fn ch))
    (println "importing dataset" title)
    (jdbc/execute-one! conn [create-sql])
    (loop [batch (async/<!! ch)]
      (when batch
        (jdbc/with-transaction [txn conn]
          (jdbc/execute-batch! conn insert-sql (map data-fn batch) {}))
        (recur (async/<!! ch))))))


