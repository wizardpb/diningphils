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
;; Each philosopher is represented as a thread and a state map. The thread repeatedly takes forks, eats, frees forks and thinks
;; until all the food is gone. The state map holds the eating state, the forks to the left and right of the philosopher,
;; and a food amount.
;;
;; Edsger Dijkstra's famous dining philosophers problem, solved using the resource hierarchy algorithm and Clojure's STM
;;
;; The forks are a vector of refs that hold nil indicating the fork is free, or an integer id for the current eater holding it.
;; Each philosopher is a state vector held in a ref, and a thread that moves repeatedly through three states: hungry, eating, thinking.
;; Each philosopher starts out hungry.
;;
;; Deadlock is avoided using the 'lowest fork first'/resource hierarchy approach
;;
;; To run with status display, evaluate:
;;
;; (start)
;;

(ns diningphils.res-hi.core
  (:require [diningphils.utils :refer :all]))

;(set-debug (range (count phil-refs)))
(set-debug #{})
;(set-debug #{1})

(def parameters
  {
   :food-amount 10
   :retry-interval 5
   :eat-range [10000 2000]
   :think-range [10000 2000]
  } )

(def philosophers
  "Our philosopher names"
  ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"])

;; This set of params causes extreme contention on the forks
;; (def parameters
;;   {
;;    :food-amount 10
;;    :retry-interval 5
;;    :eat-range [1000 200]
;;    :think-range [1000 200]
;;   } )

;; A communal food bowl
(def food-bowl
  (atom (:food-amount parameters)))

(defn get-food
  "Get some food from the food bowl"
  []
  (swap! food-bowl dec))

(defn food-left? []
  " Is there any food left ?"
  (not (zero? @food-bowl)))

;; Forks are a vector of refs, one for each philosopher.
(def forks (vec (map ref (repeat (count philosophers) nil))))

; Forks-for returns a vector of two forks for a given philosopher. It arranges that the first fork is
; always the lowest numbers fork. When dining, a philosopher always takes the first (lowest) fork first, thus
; implementing the condition that ensures a deadlock-free solution
(defn forks-for [phil-id]
  (let [fork-size (count forks)
        fork-ids (sort [(mod phil-id fork-size) (mod (inc phil-id) fork-size)])]
    (debug-pr "" phil-id "gets forks " fork-ids)
    (vec (map #(nth forks %) fork-ids))))

(defn fork-id
  " Return the id (index) of  fork (ref)"
  [fork]
  (count (take-while #(not (identical? % fork)) forks)))

; The vector of philosopher states - each is a ref holding the satet. Saved back into 'philosophers'
(def phil-refs
  (doall
    (map #(ref {:name %1 :id %2 :forks (forks-for %2) :state nil})
      philosophers
      (range (count philosophers)))))

;; Debug utility
(defn debug-phil
  [phil & args]
  (apply debug-pr (:name @phil) (:id @phil) "state=" (:state @phil) ", food=" @food-bowl " " args))

;; State printing
(defn state-string
  [phil]
  (let [
         p @phil]
    (str (:name p)
      ": forks: " (vec (map fork-id (filter #(= (deref %) (:id p)) (:forks p))))
      ", " (:state p))))

(defn show-state [phil]
  (if
    (not (debugging?))
    (do
      (log (line-escape 0) "Food left: " @food-bowl)
      (log (line-escape (+ 3 (:id @phil))) (state-string phil))
      (log (line-escape 11 8)))))

;; Basic primitives to pick up and put down forms - must be called inside a transaction
;; pick-up should always pick up a free fork, and allocate it to the indicated philosopher
(defn has-fork?
  "Is a philosopher using a fork ?"
  [phil fork]
  (= @fork (:id @phil)))

(defn pick-up [fork phil]
  (assert (nil? @fork))
  (ref-set fork (:id @phil))
  true)

; put-down should always free a fork for the correct philosopher
(defn put-down [fork phil]
  (assert (has-fork? phil fork))
  (ref-set fork nil))

; Take a fork. If the fork isn't avalable, wait and retry until it is.
; When this function returns, the taking philosophers has the given fork: (= @fork (:id @phil))
(defn take-fork [fork phil]
  (while
    (not (dosync (if (nil? @fork) (pick-up fork phil))))
    (Thread/sleep (:retry-interval parameters)))
  (show-state phil)
  (debug-phil phil "Got fork " (fork-id fork))
  )

(defn drop-fork [fork phil]
  (dosync (put-down fork phil))
  (debug-phil phil "Dropped fork " @fork))

; The philosopher is hungry. Try to grab the forks, when I have them both I'm ready to eat
; Grab the forks in the right order - ensures no deadlock
(defn hungry [phil]
  (debug-phil phil "Going hungry ...")
  (dosync
    (alter phil assoc :state "hungry")
    (show-state phil))
  (doseq [f (:forks @phil)] (take-fork f phil)))

; Make the philosopher eat. Update our status and sleep. When he's done eating, release both forks in order
; Note we don't really need to do the status update transactionally because the
; fork synchronization ensures that this is called serially; however, we want a stable view to monitor
; status, so we use refs and transactions
(defn eat [phil]
  (let
    [
      eat-duration (random-from-range (:eat-range parameters))
      now-eating (dosync
                   (if (food-left?)
                     (do
                       (assert
                         (every? true? (map = (map deref (:forks @phil)) (repeat 2 (:id @phil))))) ; We should have both forks
                       (get-food)
                       (alter phil assoc :state "eating")
                       (show-state phil)
                       (debug-phil phil "Eating for " (float (/ eat-duration 1000)) " seconds...")
                       true)
                     false))
      ]
    (if now-eating
      (Thread/sleep eat-duration)
      (debug-phil phil "No food left")))
  ;; In either case, drop all forks if we have them
  (doseq [f (:forks @phil)] (if (has-fork? phil f) (drop-fork f phil))))

; Make the philosopher think. The thread just sleeps
(defn think [phil]
    (let
      [think-duration (random-from-range (:think-range parameters))
       now-thinking (dosync
                      (if (food-left?)
                        (do
                          (alter phil assoc :state "thinkng")
                          (show-state phil)
                          (debug-phil phil "Thinking for " (float (/ think-duration 1000)) " seconds...")
                          true)
                        false))]
      (if now-thinking (Thread/sleep think-duration))))

; Thread behavior for each philosopher - while there is food he gets hungry, eats then thinks.
; He starts out hungry
(defn run-philosopher [phil]
  (Thread/sleep 100)
  (while (food-left?)
    (do
      (hungry phil)
      (eat phil)
      (think phil)))
  (dosync (alter phil assoc :state "stopped"))
  (debug-phil phil "Done")
  (show-state phil)
  )

;; (defn start-system []
;;   (doseq [phil philosophers]
;;     (.start (Thread. #(run-philosopher phil)))))

;; (defn stop-system []
;;   (dosync
;;    (doseq [phil philosophers]
;;      (assoc philosophers (:id phil) (assoc phil :eating? false :food 0)))))

(defn start []
  (clear-screen)
  (doseq [phil phil-refs]
    (future
      (debug-phil phil "Starting ...")
      (run-philosopher phil)))
  (print (line-escape 10)))

(defn stop []
  (swap! food-bowl (fn [_] 0))
  true)

;(start)
;(stop)

