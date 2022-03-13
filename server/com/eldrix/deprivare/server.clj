(ns com.eldrix.deprivare.server
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.tools.logging.readable :as log]
            [io.pedestal.http :as http]
            [io.pedestal.http.content-negotiation :as conneg]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as intc]
            [com.eldrix.deprivare.core :as deprivare]))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))
(def not-found (partial response 404))

(def supported-types ["application/json" "application/edn" "text/plain"])
(def content-neg-intc (conneg/negotiate-content supported-types))

(defn make-key-string [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(defn transform-content
  [body content-type]
  (when body
    (case content-type
      "text/html" body
      "text/plain" body
      "application/edn" (pr-str body)
      "application/json" (json/write-str body :key-fn make-key-string))))

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "application/json"))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave
   (fn [context]
     (if (get-in context [:response :headers "Content-Type"])
       context
       (update-in context [:response] coerce-to (accepted-type context))))})

(def entity-render
  "Interceptor to render an entity '(:result context)' into the response."
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})


(def get-uk-lsoa-deprivation
  {:name
   ::get-uk-lsoa-deprivation
   :enter
   (fn [context]
     (let [svc (get-in context [:request ::service])
           pc (get-in context [:request :path-params :lsoa])]
       (if-not pc
         context
         (assoc context :result (deprivare/fetch-lsoa svc pc)))))})

(def common-interceptors [coerce-body content-neg-intc entity-render])
(def routes
  (route/expand-routes
    #{["/v1/uk/lsoa/:lsoa" :get (conj common-interceptors get-uk-lsoa-deprivation)]}))

(defn inject-svc
  "A simple interceptor to inject service 'svc' into the context."
  [svc]
  {:name  ::inject-svc
   :enter (fn [context] (update context :request assoc ::service svc))})

(def service-map
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8080})

(defn make-service-map [svc port join?]
  (-> service-map
      (assoc ::http/port port)
      (assoc ::http/join? join?)
      (http/default-interceptors)
      (update ::http/interceptors conj (intc/interceptor (inject-svc svc)))))

(defn start-server
  ([svc port] (start-server svc port true))
  ([svc port join?]
   (http/start (http/create-server (make-service-map svc port join?)))))

(defn run-server [{:keys [db port]}]
  (if-not db
    (println (str/join "\n" ["Error: missing :db parameter"
                             "Usage: clj -X:server :db <database file> :port <port>"
                             "Parameters:"
                             "  - :db     : path to database"
                             "  - :port   : HTTP port to use, optional, default 8080"]))
    (with-open [svc (deprivare/open (str db))]
      (log/info "starting server on port " port)
      (start-server svc port))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; For interactive development
(defonce server (atom nil))

(defn start-dev [svc port]
  (reset! server
          (http/start (http/create-server (make-service-map svc port false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart [svc port]
  (stop-dev)
  (start-dev svc port))

