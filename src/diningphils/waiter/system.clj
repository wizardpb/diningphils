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
     :food-bowl     (atom (:food-amount params))
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
  (-> sys
    (assoc :phils (vec (map #(future (run-phil %1 sys)) (range (count (:phil-names sys))))))
    (assoc :waiter (future (run-waiter sys)))))


(defn wait-for-done [sys]
  (let [phils (:phils sys)
        end-ch (a/thread
                 (doseq [phil phils] (try @phil (catch CancellationException e)))
                 (future-cancel (:waiter sys))
                 "Finished")
        stop-ch (a/thread
                  (show-line (+ (count phils) 6) "Press return to stop")
                  (read-line)
                  (doseq [pf phils] (future-cancel pf))
                  "Stopped")
        [val _] (a/alts!! [end-ch stop-ch])
        ;[val _] (a/alts!! [end-ch])
        ]
    (show-line (+ (count phils) 6) (str val "\n"))
    'Done))
-
(defn init
  ([p] (sys/init (partial init-fn p)))
  ([] (sys/init (partial init-fn {}))))

(defn start []
  (sys/start start-fn))

(defn go
  ([p]
   (init p)
   (start)
   (wait-for-done sys/system))
  ([] (go {})))
