;;
;; Dining philosophers solution using Chandy-Misra algorithm
;; https://www.cs.utexas.edu/users/misra/scannedPdf.dir/DrinkingPhil.pdf
;;
;; Copyright 2015 Prajna Inc.
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
;; Edsger Dijkstra's famous dining philosophers problem, solved using the using Chandy-Misra algorithm and Clojure
;; agents
;;
;; See the Chandry-Misra paper (C-M) at https://www.cs.utexas.edu/users/misra/scannedPdf.dir/DrinkingPhil.pdf
;;
;; Philosphers are represented as agents, which hold the philospher state.
;;
;; Each is a state machine which implements the main C-M guarded command. State changes are driven by message sends
;; from ajoining philosophers. When a philosopher gets hungry, it requests the forks it doesn't have, then begins
;; eating when they arrive. After a while, it becomes sated, and goes back to thinking until it becomes hungry again.
;;
;; Messages between philosophers (agents) are implemented as agent sends (and sent using send-off because of state
;; monitoring). To save having to pass a new state map around between various functions that access and manipulate it,
;; message execution is wrapped by a function (execute-message) that places the current state into thread-local vars.
;; This includes the var *state*, which holds the new state to be set on message completion. Any function that
;; updates the state places the new state map into this var, which is finally returned as the new state for the agent.
;;
;; For brevity, other read-only values from the agent state (such as the id's of adjoining philosopher agents,
;; needed for message sending) are also placed into thread-local vars.
;;
;; 'execute-message' also takes care of calling the main state machine function (state-changed). This implements
;; the C-M guarded command as described above.
;;
;; The forks themselves are represented explicitly by a vector of atoms holding the state of each fork. This
;; indicates whether it is dirty or not. It also includes who is currently using it, which is solely for monitoring
;; purposes. Note that we do not need transactional semantics on the fork state update, because the message sequencing
;; of the C-M algorith ensures that only one philosopher will ever try to update the state at any time.
;;
;; There is a fixed amount of food, and this goes on until all the food is gone. When a philosopher
;; goes hungry and there is no food left it rests. The system is done when all philosophers are resting.
;;
;; To run with status display, evaluate:
;;
;; (go)
;;
;; It can be stopped at any time by pressing any key
;;

(ns diningphils.c-m-agents.system
  (:require [diningphils.system :as sys])
  (:use [diningphils.utils]
        [diningphils.c-m-agents.core])
  )

(defn connect [state agents]
  (let [n (count agents)
        id (:phil-id state )
        left (nth agents (mod (dec id) n))
        right (nth agents (mod (inc id) n))]
    (assoc state :neighbors [left right])
    ;state
    ))

(defn connect-agents [agents]
  (doseq [a agents]
    (set-error-handler! a (fn [a e]
                            (log "Philosopher " (:phil-id @a) " throws " e)
                            (.printStackTrace e)))
    (set-error-mode! a :continue)
    (send a connect agents))
  (apply await-for 1000 agents)
  agents)

(defn init-fn [p]
  (let [phil-names ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"]
        phil-count (count phil-names)
        base-params {:food-amount 10 :eat-range [10000 2000] :think-range [10000 2000]}
        params (merge base-params p)
        forks (mapv #(atom (initialized-fork %)) (range phil-count))]
    {
     :parameters params
     :phil-names phil-names
     :phil-count phil-count
     :food-bowl  (ref (if-let [f (:food-amount params)]
                        (repeatedly f (partial random-from-range (:eat-range params)))
                        (repeatedly (partial random-from-range (:eat-range params)))))
     :forks      forks
     :phils      (connect-agents (map-indexed #(agent (initial-agent-state %1 %2 forks)) phil-names))
     }
    ))

(defn start-fn [sys]
  (clear-screen)
  (doseq [phil (range (count (:phil-names sys)))] (run-phil sys/system phil))
  sys)

(defn clean-fn [sys]
  (while (not (every? #(= :done %) (map #(:state (deref %)) (:phils  sys/system))))
    (Thread/sleep 1000))
  )

(defn stop-fn [sys]
  (doseq [phil (:phils  sys)]
    (send-message phil done)
    (await phil))
  sys
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

