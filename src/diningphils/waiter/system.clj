(ns diningphils.waiter.system
  (:require [clojure.core.async :as a]
            [diningphils.system :as sys])
  (:use [diningphils.utils]
        [diningphils.waiter.core])
  (:import (java.util.concurrent CancellationException)))

(defn init-fn [p]
  (let [phil-names ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"]
        base-params {:food-amount 10 :eat-range [10000 2000] :think-range [10000 2000]}
        params (merge base-params p)
        to-chans (vec (repeatedly (count phil-names) (partial a/chan 1)))]
    {
     :parameters    params
     :phil-names    phil-names
     :food-bowl     (ref (if-let [f (:food-amount params)]
                           (repeatedly f (partial random-from-range (:eat-range params)))
                           (repeatedly (partial random-from-range (:eat-range params)))))
     :to-chans      to-chans
     :from-chans    (vec (repeatedly (count phil-names) (partial a/chan 1)))
     :chan-indices  (into {} (map-indexed #(vector %2 %1)) to-chans)
     :forks         (vec (repeatedly (count phil-names) (partial atom nil)))
     :fork-requests (vec (repeatedly (count phil-names) (partial atom nil)))
     }
    ))

(defn start-fn [sys]
  (clear-screen)
  (Thread/sleep 500)
  (let [cnt (count (:phil-names sys))]
    (-> sys
     (assoc :phils (vec (map #(future (run-phil sys %1 %1 %2))
                          (range cnt)
                          (take cnt (rest (cycle (range 5)))))))
     (assoc :waiter (future (run-waiter sys))))))

(defn stop-waiter [sys]
  (a/>!! (first (:to-chans sys)) :end))

(defn clean-fn [sys]
  (doseq [phil (:phils sys)] (try @phil (catch CancellationException e)))
  (stop-waiter sys)
  @(:waiter sys))

(defn stop-fn [sys]
  (doseq [phil (:phils sys)] (future-cancel phil))
  (stop-waiter sys)
  @(:waiter sys))

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

;(set-debug (range 5))
