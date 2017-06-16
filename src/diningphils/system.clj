(ns diningphils.system
  (:require [clojure.core.async :as a])
  (:use [diningphils.utils]))

(def system)

(defn init
  "Constructs the current development system."
  [sys-fn]
  (alter-var-root #'system
    (constantly (sys-fn))))

(defn start
  "Starts the current development system."
  [start-fn]
  (alter-var-root #'system start-fn))

(defn stop
  "Shuts down and destroys the current development system."
  [stop-fn]
  (alter-var-root #'system
    (fn [s] (when s (stop-fn s)))))

(defn wait-for-done [clean-fn stop-fn]
  (let [phils (:phils system)
        end-ch (a/thread
                 (clean-fn system)
                 "Finished")
        stop-ch (a/thread
                  (show-line (+ (count phils) 6) "Press return to stop")
                  (read-line)
                  (stop stop-fn)
                  "Stopped")
        [val _] (a/alts!! [end-ch stop-ch])
        ;[val _] (a/alts!! [end-ch])
        ]
    (show-line (+ (count phils) 6) val "\n")
    'Done))
