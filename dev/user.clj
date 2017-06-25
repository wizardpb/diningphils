 (ns user
   (:require [clojure.pprint :refer :all]
             [clojure.tools.namespace.repl :refer (refresh refresh-all)]
             [clojure.java.classpath :as cp]
             [diningphils.system :as sys]
             [clojure.pprint :as p]
             )
   (:use
     diningphils.c-m-async.core
     diningphils.c-m-async.system
     diningphils.utils))

;(set-debug (range 5))
(alter-var-root #'*print-level* (constantly 5))






