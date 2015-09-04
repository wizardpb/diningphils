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

(ns diningphils.res-hi)

(def parameters
  {
   :food-amount 10
   :retry-interval 5
   :max-eat-duration 2000
   :max-think-duration 2000
  } )

;; This set of params causes extreme contention on the forks
;; (def parameters
;;   {
;;    :food-amount 10
;;    :retry-interval 5
;;    :max-eat-duration 2000
;;    :max-think-duration 20
;;   } )

(defmacro exec-time
  "Evaluates expr and returns the time it took."
  [expr]
  `(let [start# (. System (nanoTime))]
    (~expr)
    (/ (double (- (. System (nanoTime)) start#)) 1000000.0)))

; The names of the dining philosophers. Their position in the vector determines their id
(def philosophers ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"])

; Forks are a vector of refs, one for each philosopher.
(def forks (vec (map ref (repeat (count philosophers) nil))))

; Basic primitives to pick up and put down forms
; Both return true

; pick-up should always pick up an free fork, and allocate it to the indicated philosopher
(defn pick-up [fork phil]
  (assert (nil? @fork))
  ;(println "pick-up " fork)
  (ref-set fork (:id @phil))
  true)

; put-down should always free a fork for the correct philosopher
(defn put-down [fork phil]
  (assert (= @fork (:id @phil)))
  ;(println "put-down " fork)
  (ref-set fork nil))

; Take a fork. If the fork isn't avalable, wait and retry until it is.
; When this function returns, the taking philosophers has the given fork: (= @fork (:id @phil))
(defn take-fork [fork phil]
  ;(println "take " fork)
  (while
    (not (dosync (if (nil? @fork) (pick-up fork phil))))
    (Thread/sleep (:retry-interval parameters))))

(defn drop-fork [fork phil]
  (dosync (put-down fork phil)))

; Forks-for returns a vector of two forks for a given philosopher. It arranges that the first fork is
; always the lowest numbers fork. When dining, a philosopher always takes the first (lowest) fork first, thus
; implementing the condition that ensures a deadlock-free solution
(defn forks-for [phil-id]
  (let [fork-size (count forks)]
  (vec (map #(nth forks %) (sort [(mod phil-id fork-size) (mod (inc phil-id) fork-size)])))))

; Make a philosopher state from a name and its id
(defn make-philosopher [name phil-id]
  (ref {:name name :id phil-id :forks (forks-for phil-id) :state nil :food (:food-amount parameters)}))

; The vector of philosopher states - saved back into 'philosophers'
(def philosophers
  (doall (map #(make-philosopher %1 %2)
              philosophers
              (range (count philosophers)))))

; The philosopher is hungry. Try to grab the forks, when I have them both I'm ready to eat
; Grab the forks in the right order - ensures no deadlock
(defn hungry [phil]
  (dosync (alter phil assoc :state "hungry"))
  (doseq [f (:forks @phil)] (take-fork f phil)))

; Make the philosopher eat. Update our status and sleep. When he's done eating, release both forks in order
; Note we don't really need to do the status update transactionally because the
; fork synchronization ensures that this is called serially; however, we want a stable view to monitor
; status, so we use refs and transactions
(defn eat [phil]
  (dosync
   (assert (every? true? (map = (map deref (:forks @phil)) (repeat 2 (:id @phil))))) ; We should have both forks
   (alter phil assoc :state "eating"))
  (do
    (Thread/sleep (rand-int (:max-eat-duration parameters)))
    (doseq [f (:forks @phil)] (drop-fork f phil)))
  )

; Make the philosopher think. The thread just sleeps
(defn think [phil]
  (dosync
   (alter phil update-in [:food] dec)
   (alter phil assoc :state "thinkng"))
  (Thread/sleep (rand-int (:max-think-duration parameters))))

; Thread behavior for each philosopher - while there is food he gets hungry, eats then thinks.
; He starts out hungry
(defn run-philosopher [phil]
  (while (not (zero? (:food @phil)))
    (do
      (hungry phil)
      (eat phil)
      (think phil))))

; Print a status line for each philosopher
(defn status []
  (print"\033[2J\033[0;0H")
  (dosync
    (doseq [p-ref philosophers]
	  (let [p @p-ref
          pfn #(if (nil? %) "free" % )]
      	(println (str (:name p)
                  ": forks: [" (pfn @(first (:forks p))) "," (pfn @(last (:forks p)))
                  "], " (:state p)
                  ", food: " (:food p)))))))

;; (defn start-system []
;;   (doseq [phil philosophers]
;;     (.start (Thread. #(run-philosopher phil)))))

;; (defn stop-system []
;;   (dosync
;;    (doseq [phil philosophers]
;;      (assoc philosophers (:id phil) (assoc phil :eating? false :food 0)))))

(defn start-system []
  (doseq [phil philosophers]
    (.start (Thread. #(run-philosopher phil)))))

(defn stop-system []
  (dosync
   (doseq [phil philosophers]
     (alter phil assoc :eating? false)
     (alter phil assoc :food 0))))

(defn done-eating? [phil]
  (let [philMap (deref phil)] (and (not (:eating? philMap)) (zero? (:food philMap)))))

(defn all-done-eating? []
  (dosync
   (every? true? (map done-eating? philosophers))))

(def run (atom false))

(defn stop []
  (if @run
    (do
      (swap! run not)
      (stop-system)
      (println "System stoppped"))
    (println "System not running"))
  true)

(defn status-loop []
  (while @run
    (do
      (status)
      (if (all-done-eating?)
        (stop)
        (Thread/sleep 1000)))))

(defn start []
  (if (not @run)
    (do
      (swap! run not)
      (start-system)
      (.start (Thread. #(status-loop))))
    (println "System already running"))
  true)

;; (start)
;; (stop)

