(ns com.eldrix.uk-depriv.server
  (:require [clojure.tools.logging.readable :as log]))

(defn run-server [{:keys [port]}]
  (log/info "starting server on port " port)
  )