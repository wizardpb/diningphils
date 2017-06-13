(ns diningphils.system)

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
