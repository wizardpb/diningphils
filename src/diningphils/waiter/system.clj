;;
;; Copyright 2017 Prajna Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Edsger Dijkstra's famous dining philosophers problem, solved using a separate 'Waiter' process to allocate forks
;;
;; Each philosoher is represented as a thread (a future) that repeated gets hungry, asks the wait for
;; forks and food, eats and thinks. The waiter prevents deadlock by synchronizing the allocation of forks for
;; each philosopher. For each fork, it tracks ownership and requests. Since each fork is shared between two
;; philosophers, the fork is either free or owned by one philosopher, with the other philosopher possible requesting
;; it. This state can therefore be represented with two atoms per fork, one holding the owner and one holding a
;; requester. A nil value in the owner atom indicates the fork is free.
;;
;; The waiter is connected to each philosopher by two core.async channels, over which it receives requests and provides
;; resources. Requests are sent by the philosophers in the form of lists which are treated as literal representation
;; of function calls. Implenting these functions provides the sent of requests the waiter understands
;;
;; To run with status display, evaluate:
;;
;; (go)
;;
;; from the diningphils.waiter.system namespace. Hitting any key will stop the simulation.
;;

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
