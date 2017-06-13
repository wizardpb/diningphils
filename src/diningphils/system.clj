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

(defn go
  "Initializes the current development system and starts it running."
  [sys-fn start-fn]
  (init sys-fn)
  (start start-fn))