(ns com.eldrix.deprivare.graph
  (:require [com.eldrix.deprivare.core :as depriv]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.plugin :as p.plugin]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.interface.eql :as p.eql]))


(pco/defresolver indices-by-lsoa
  "Returns deprivation indices by UK LSOA"
  [{::keys [svc]} {:uk.gov.ons/keys [lsoa]}]
  {::pco/output [:uk-composite-imd-2020-mysoc/UK_IMD_E_rank
                 :uk-composite-imd-2020-mysoc/UK_IMD_E_pop_decile]}
  (depriv/fetch-lsoa svc lsoa))
