 (ns user
   (:require [clojure.pprint :refer :all]
             [clojure.tools.namespace.repl :refer (refresh refresh-all)]
             [clojure.java.classpath :as cp]
             [diningphils.system :as sys]
             [clojure.pprint :as p]
             )

   ;; Uncomment one of these :use statements to include the version you want to run.

   ;(:use
   ;  diningphils.c-m-async.core
   ;  diningphils.c-m-async.system)
   ;(:use
   ;  diningphils.c-m-agents.core
   ;  diningphils.c-m-agents.system)
   ;(:use
   ;  diningphils.res-hi.core
   ;  diningphils.res-hi.system)
   ;(:use
   ;  diningphils.waiter.core
   ;  diningphils.waiter.system)
   )

;(set-debug (range 5))
(alter-var-root #'*print-level* (constantly 5))






