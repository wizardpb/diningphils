(defproject diningphils "1.0.0-SNAPSHOT"
  :description "Dining Philosophers solution"
  :dependencies [
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.442"]]
  :profiles {
             :dev {:source-paths ["src" "dev"]
                   :dependencies [[org.clojure/tools.nrepl "0.2.11"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [org.clojure/java.classpath "0.2.3"]]}
             })
