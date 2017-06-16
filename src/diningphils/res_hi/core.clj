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
  (:require [diningphils.utils :refer :all]
            [diningphils.system :as sys]))

(def ^:dynamic *phil-id*)
(def ^:dynamic *phil-name*)
(def ^:dynamic *left-fork-id*)
(def ^:dynamic *right-fork-id*)
(def ^:dynamic *lower-fork*)
(def ^:dynamic *higher-fork*)

(defn show-state [& args]
  (show-line 1 "food left:" (let [f @(:food-bowl sys/system)] (if f f "unlimited")))
  (apply show-line (+ *phil-id* 3) (str *phil-name* ":") args))

(defn take-fork [fork]
  (while (not= (swap! fork #(if (nil? %) *phil-id* %)) *phil-id*)
    (Thread/sleep 100)))

(defn get-forks []
  (show-state "hungry, waiting for forks" *left-fork-id* "and" *right-fork-id*)
  (take-fork *lower-fork*)
  (let [[l-id u-id] (sort [*left-fork-id* *right-fork-id*])]
    (show-state "hungry, has fork" l-id "waiting for" u-id))
  (take-fork *higher-fork*))

(defn drop-forks []
  (swap! *lower-fork* (constantly nil))
  (swap! *higher-fork* (constantly nil)))

(defn fork-ids-for [phil-id]
  (let [forks (:forks sys/system)]
    [phil-id (mod (inc phil-id) (count (:phil-names sys/system)))]))

(defn get-food []
  (dosync
    (let [fb (:food-bowl sys/system)
          food-left @fb]
      (if (and food-left (> food-left 0)) (alter fb dec))
      (or (nil? food-left) (> food-left 0)))))

(defn eat []
  (show-state "eating...")
  (Thread/sleep (random-from-range (get-in sys/system [:parameters :eat-range])))
  (drop-forks))

(defn think []
  (show-state "thinking...")
  (Thread/sleep (random-from-range (get-in sys/system [:parameters :think-range]))))

(defn run-phil [phil-id]
  (let [fork-ids (fork-ids-for phil-id)
        left-id (first fork-ids)
        right-id (last fork-ids)]
    (binding [*phil-id* phil-id
              *phil-name* (nth (:phil-names sys/system) phil-id)
              *left-fork-id* left-id
              *right-fork-id* right-id
              *lower-fork* (nth (:forks sys/system) (if (> left-id right-id) right-id left-id))
              *higher-fork* (nth (:forks sys/system) (if (> left-id right-id) left-id right-id))
              ]
      (Thread/sleep (random-from-range [1 10]))
      (loop []
        ;; Start out hungry
        (get-forks)
        (if (get-food)
          (do
            (eat) (think)
            (recur))
          (do
            (drop-forks)))
        )
      (show-state "Done."))))