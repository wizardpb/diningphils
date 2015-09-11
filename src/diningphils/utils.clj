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
;; Some utility functions for solving Edsger Dijkstra's famous dining philosophers problem
;;
;;
(ns diningphils.utils)

;; Status printing
(def logger
  "An agent that logs status messages. Using an agent allows printing to be atomic"
  (agent 0))

(defn log
  "Atomically println args to *out* along with a newline"
  [& args]
  (send-off logger
    (fn [_]
      (println (apply str args))
      (flush)
      _)))

(def debug-phils
  "The phil-ids to include in pr-status tracing. Initial there is no debugging"
  (atom #{}))

(defn set-debug [phil-ids]
  (swap! debug-phils (fn [_] (set phil-ids))))

(defn debugging? []
  (not (empty? @debug-phils)))

(defn line-escape
  [& args]
  (let [ [row col] (condp = (count args)
                     0 [1 1]
                     1 [(first args) 0]
                     2 [(first args) (second args)]
                     )]
    (str "\033[" row ";" col "H\033[K")))

(defn clear-screen [] (print "\033[2J\033[1;1H"))

(defn debug-pr
  "Send a status string down the status channel, but only if we are on a philosopher thread whose *phil-id* is in
  pr-phils"
  [phil-name phil-id & args]
  (if (contains? @debug-phils phil-id)
    (log phil-name "(" phil-id ") debug: " (apply str args))))

(defn random-from-range
  "Return a random integer from the range-vec [max min]"
  [range-vec]
  (+ (second range-vec) (rand-int (apply - range-vec))))


