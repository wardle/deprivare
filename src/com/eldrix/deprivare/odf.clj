(ns com.eldrix.deprivare.odf
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip])
  (:import (java.util.zip ZipFile ZipInputStream)
           (java.io InputStream File)))

(xml/alias-uri 'office "urn:oasis:names:tc:opendocument:xmlns:office:1.0")
(xml/alias-uri 'table "urn:oasis:names:tc:opendocument:xmlns:table:1.0")
(xml/alias-uri 'text "urn:oasis:names:tc:opendocument:xmlns:text:1.0")


(defn sheet-names
  "Returns the sheet names from the opendocument file specified.
  Parameters:
   - f  : anything coercible to a file using [[clojure.java.io/as-file]]"
  [f]
  (with-open [zf (ZipFile. ^File (io/as-file f))]
    (when-let [entry (.getEntry zf "content.xml")]
      (-> (.getInputStream zf entry)
          (xml/parse :skip-whitespace true)
          (zip/xml-zip)
          (zx/xml-> ::office/document-content
                    ::office/body
                    ::office/spreadsheet
                    ::table/table
                    (zx/attr ::table/name))))))

(defn- parse-row
  [node]
  (zx/xml-> (zip/xml-zip node) ::table/table-row ::table/table-cell ::text/p zx/text))

(defn sheet-rows
  "Return the rows from the sheet named.
  Parameters:
   - f          : anything coercible by [[clojure.java.io/as-file]]
   - table-name : name of table"
  [f table-name]
  (with-open [zf (ZipFile. ^File (io/as-file f))]
    (when-let [entry (.getEntry zf "content.xml")]
      (map parse-row (-> (.getInputStream zf entry)
                         (xml/parse :skip-whitespace true)
                         (zip/xml-zip)
                         (zx/xml-> ::office/document-content
                                   ::office/body
                                   ::office/spreadsheet
                                   ::table/table
                                   (zx/attr= ::table/name table-name)
                                   ::table/table-row
                                   clojure.zip/node))))))

(defn sheet-data
  "Returns data from the sheet specified as a sequence of maps.
  Parameters:
   - f          : anything coercible by [[clojure.java.io/as-file]]
   - table-name : name of spreadsheet table
  Options:
   - headings      : keys for each value
   - pred          : predicate, for rows to include

  If no headings are specified, the first row will be used.

  Some spreadsheets are not correctly organised. For example, some have a row or
  two at the top with information about the dataset. This is frustrating and
  makes them more difficult for machine-reading. The predicate is designed
  to help screen out such rows."
  [f table-name & {:keys [headings pred] :or {pred (constantly true)}}]
  (let [rows (filter pred (sheet-rows f table-name))
        headings' (repeat (if headings headings
                               (map (fn [s] (keyword (str/lower-case (str/replace (str/replace s #"\p{Punct}" "") #"\W" "_")))) (first rows))))]
    (if headings
      (map zipmap headings' rows)
      (map zipmap headings' (rest rows)))))


(comment

  ::office/body                                             ;;- > now resolves to a properly namespaced XML namespace
  (def f "/Users/mark/Downloads/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods")
  (doc-sheet-names f)
  (doc-sheet-data f "Deciles_quintiles_quartiles")
  (take 4 (rest (doc-sheet-data f "Deciles_quintiles_quartiles" :headings [:lsoa :nm :la :wimd2019 :decile :quintile :quartile] :pred #(= 7 (count %)))))
  (def is (io/input-stream ))
  (sheet-names is)
  (take 10 (sheet-rows is "WIMD_2019_ranks"))
  (def zis (ZipInputStream. is))
  (def reader (loop [entry (.getNextEntry zis)]
                (println "entry:" entry)
                (if (or (not entry) (= "content.xml" (.getName entry)))
                  (when entry (io/reader zis))
                  (recur (.getNextEntry zis)))))
  (def data (xml/parse reader :skip-whitespace true))
  (dissoc data :content)

  (def url (java.net.URL. "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"))
  (def is (.openStream url))
  is
  (def content (is-from-zipfile is "content.xml"))
  (def rows (sheet-rows content "WIMD_2019_ranks"))
  (def rows' (filter #(>= (count %) 12) rows))
  (take 2 rows')
  (map (fn [s] (keyword (clojure.string/lower-case (clojure.string/replace s " " "")))) (first rows'))
  (def is (io/input-stream "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"))


  (require '[clj-http.client :as client])
  (clj-http.client/get "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods" {:headers {"User-Agent" "deprivare v0.1"} :as :stream})
  (def f (java.io.File/createTempFile "wmd-2019" ".ods"))
  (io/copy is f)
  (System/setProperty "http.agent" "Mozilla/5.0 (Windows NT 6.1;) Gecko/20100101 Firefox/13.0.1")
  (require '[clj-http.client :as client])
  (def downloaded (clj-http.client/get "https://gov.wales/sites/default/files/statistics-and-research/2019-11/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods"
                                       {:headers       {"User-Agent" "Deprivare 0.1"}
                                        :max-redirects 5 :redirect-strategy :lax
                                        :as            :byte-array}))
  (if (= 200 (:status downloaded))
    (:body downloaded)
    (throw (ex-info "unable to download file" (dissoc downloaded :body))))
  (keys downloaded)
  (:status downloaded)
  (type (:body downloaded))
  (def is (io/input-stream (:body downloaded)))

  (doc-sheet-names "/Users/mark/Downloads/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods")
  (require '[clojure.string :as str])
  (def data (doc-sheet-data "/Users/mark/Downloads/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods" "WIMD_2019_ranks" :pred #(>= (count %) 12)))
  (take 5 data)
  (take 10 (reverse (sort-by :wimd_2019 data)))
  (def headings (keys (first data)))
  (take 4 (doc-sheet-data "/Users/mark/Downloads/welsh-index-multiple-deprivation-2019-index-and-domain-ranks-by-small-area.ods" "WIMD_2019_ranks" :column-names headings)))
