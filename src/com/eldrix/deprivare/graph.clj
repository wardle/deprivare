(ns com.eldrix.deprivare.graph
  (:require [com.eldrix.deprivare.core :as depriv]
            [com.eldrix.deprivare.datasets :as datasets]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql])
  (:import (com.eldrix.deprivare.core Svc)))


(defn make-lsoa-resolver
  "Dynamically create a graph resolver based on the installed datasets
  within the service specified."
  [^Svc svc]
  (pco/resolver {::pco/op-name 'indices-by-lsoa
                 ::pco/input   [:uk.gov.ons/lsoa]
                 ::pco/output  (vec (datasets/properties-for-datasets (depriv/fetch-installed svc)))
                 ::pco/resolve (fn [_env {:uk.gov.ons/keys [lsoa]}]
                                 (depriv/fetch-lsoa svc lsoa))}))

(defn make-all-resolvers
  "Returns dynamically generated resolver(s) for deprivation indices based
  on what is installed in the `deprivare` service specified. As this closes
  over the service itself, the pathom environment does not need any specific
  set-up."
  [^Svc svc]
  [(make-lsoa-resolver svc)])

(comment
  (def svc (depriv/open "depriv.db"))
  (def lsoa-resolver (make-lsoa-resolver svc))
  (lsoa-resolver {:uk.gov.ons/lsoa "W01000001"})
  (depriv/fetch-installed svc)
  (vec (datasets/properties-for-datasets (depriv/fetch-installed svc)))
  (def env (pci/register (make-all-resolvers svc)))
  env
  (depriv/fetch-lsoa svc "W01000001")
  (p.eql/process env {:uk.gov.ons/lsoa "W01000001"}
                 [:uk.gov.ons/lsoa :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile])
  (p.eql/process env
                 [{[:uk.gov.ons/lsoa "W01000001"]
                   [:uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}])
  )