(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.eldrix/deprivare)
(def version (format "2.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-basis (b/create-basis {:project "deps.edn", :aliases [:server]}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s-server-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Create a library jar file."
  [_]
  (clean nil)
  (println "Building" lib version)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]
                :scm       {:url                 "https://github.com/wardle/deprivare"
                            :tag                 (str "v" version)
                            :connection          "scm:git:git://github.com/wardle/deprivare.git"
                            :developerConnection "scm:git:ssh://git@github.com/wardle/deprivare.git"}
                :pom-data  [[:description "Deprivation indices in the United Kingdom"]
                            [:developers
                             [:developer
                              [:id "wardle"] [:name "Mark Wardle"] [:email "mark@wardle.org"] [:url "https://wardle.org"]]]
                            [:organization [:name "Eldrix Ltd"]]
                            [:licenses
                             [:license
                              [:name "Eclipse Public License v2.0"]
                              [:url "https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html"]
                              [:distribution "repo"]]]]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install
  "Install library to local maven repository."
  [_]
  (jar nil)
  (println "Installing" lib version)
  (b/install {:basis     basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir}))

(defn deploy
  "Deploy library to clojars.
  Environment variables CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib       lib
                                      :class-dir class-dir})}))

(defn uber
  [{:keys [out] :or {out uber-file}}]
  (println "Building uberjar " out)
  (clean nil)
  (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
  (b/copy-file {:src "server/logback.xml" :target (str class-dir "/logback.xml")})
  (b/compile-clj {:basis        uber-basis
                  :src-dirs     ["src" "server"]
                  :ns-compile   ['com.eldrix.deprivare.server]
                  :compile-opts {:elide-meta     [:doc :added]
                                 :direct-linking true}
                  :class-dir    class-dir})
  (b/uber {:class-dir class-dir
           :uber-file out
           :basis     uber-basis
           :main      'com.eldrix.deprivare.server}))
