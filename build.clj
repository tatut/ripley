(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.tatut/ripley)
(def today (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
                    (java.util.Date.)))
(def version (format "%s.%s"
                     today
                     (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/ripley.jar") ;(format "target/%s-%s.jar" (name lib) version)

(defn commit-sha []
  (b/git-process {:git-args ["rev-parse" "HEAD"]}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/tatut/ripley"
                      :connection "scm:git:git://github.com/tatut/ripley.git"
                      :tag (commit-sha)}})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
