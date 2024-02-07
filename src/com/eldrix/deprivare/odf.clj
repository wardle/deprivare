(ns com.eldrix.deprivare.odf
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip])
  (:import (java.io File)
           (java.util.zip ZipFile)))

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
  (sheet-names f)
  (take 10 (sheet-rows f "WIMD_2019_ranks"))
  (sheet-data f "Deciles_quintiles_quartiles")
  (take 4 (rest (sheet-data f "Deciles_quintiles_quartiles" :headings [:lsoa :nm :la :wimd2019 :decile :quintile :quartile] :pred #(= 7 (count %))))))
