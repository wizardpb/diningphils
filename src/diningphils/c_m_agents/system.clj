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
;; Edsger Dijkstra's famous dining philosophers problem, solved using the resource hierarchy algorithm and Clojure's STM
;;
;; Each philosopher is represented as a thread and a set of per-thread local vars. The thread repeatedly takes
;; forks, eats, frees forks and  thinks until all the food is gone. The vars holds the philosopher identity and
;; the forks to the left and right of the philosopher
;;
;; A global system map hold all the forks as vector indexed by an integer ID, run-time parameters and a food bowl
;; from which the philosopers grab food to eat. This is represented as a fixed or infinite sequence of random
;; integers representing the eat time in mS. Think time is also generated as random integers. Both are drawn from
;; a range defined by a system parameter.
;;
;; Deadlock is avoided using the 'lowest fork first'/resource hierarchy approach
;;
;; To run with status display, evaluate:
;;
;; (go)
;;
;; from the diningphils.res-hi.system namespace. Hitting any key will stop the simulation.
;;

(ns diningphils.c-m-agents.system
  (:require [diningphils.system :as sys])
  (:use [diningphils.utils]
        [diningphils.c-m-agents.core])
  )

(defn connect-agents [agents]
  (letfn [(connect [id a]
            (let [n (count agents)
                  left (nth agents (mod (dec id) n))
                  right (nth agents (mod (inc id) n))]
              (assoc a :neighbors [left right])))]
    (map-indexed connect agents)))

(defn init-fn [p]
  (let [phil-names ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"]
        base-params {:food-amount 10 :eat-range [10000 2000] :think-range [10000 2000]}
        params (merge base-params p)
        forks (vec (map #(atom (initialized-fork %)) (count phil-names)))]
    {
     :parameters params
     :phil-names phil-names
     :phil-count (count phil-names)
     :food-bowl  (ref (if-let [f (:food-amount params)]
                        (repeatedly f (partial random-from-range (:eat-range params)))
                        (repeatedly (partial random-from-range (:eat-range params)))))
     :forks      forks
     :agents     (connect-agents (vec (map (agent (initial-agent-state % forks)) (count phil-names))))
     }
    ))

(defn start-fn [sys]
  (clear-screen)
  (assoc sys :phils (vec (map #(future (run-phil sys/system %1)) (range (count (:phil-names sys)))))))

(defn clean-fn [sys]

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

