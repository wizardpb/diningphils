(ns diningphils.res-hi.system
  (:require [diningphils.system :as sys]
            [clojure.core.async :as a])
  (:use [diningphils.utils]
        [diningphils.res-hi.core])
  (:import (java.util.concurrent CancellationException)))

(defn init-fn [p]
  (let [phil-names ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"]
        base-params {:food-amount 10 :eat-range [10000 2000] :think-range [10000 2000]}
        params (merge base-params p)]
    {
     :parameters    params
     :phil-names    phil-names
     :food-bowl     (ref (:food-amount params))
     :forks         (vec (repeatedly (count phil-names) (partial atom nil)))
     }
    ))

(defn start-fn [sys]
  (clear-screen)
  (assoc sys :phils (vec (map #(future (run-phil %1)) (range (count (:phil-names sys)))))))

(defn clean-fn [sys]
  (doseq [phil (:phils sys)] (try @phil (catch CancellationException e)))
  )

(defn stop-fn [sys]
  (doseq [pf (:phils sys)] (future-cancel pf))
  )

(defn init
  ([p] (sys/init (partial init-fn p)))
  ([] (sys/init (partial init-fn {}))))

(defn start []
  (sys/start start-fn))

(defn go
  ([p]
   (init p)
   (start)
   (sys/wait-for-done clean-fn stop-fn))
  ([] (go {})))

