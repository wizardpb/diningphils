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
  (apply send-off logger (fn [_ & arghs] (println (apply str arghs)) (flush) _) args))

(def debug-phils
  "The phil-ids to include in pr-status tracing. Initial there is no debugging"
  (atom #{}))

(defn set-debug [phil-ids]
  (swap! debug-phils (fn [_] (set phil-ids))))

(defn debugging? []
  (not (empty? @debug-phils)))

(defn line-escape
  ([] (line-escape 1 1))
  ([ row ] (line-escape row 1))
  ([ row col ] (str "\033[" row ";" col "H\033[K")))

(defn clear-screen [] (print "\033[2J"))

(defn show-line [n & args]
  (print (apply str (line-escape n) args)))

(defn debug-pr
  "Send a status string down the status channel, but only if phil-id is in debug-phils or phil-id is nil and we haev
  debug ids set"
  [phil-name phil-id & args]
  (if (or
        (and (not (empty? @debug-phils)) (nil? phil-id))
        (contains? @debug-phils phil-id))
    (log phil-name "(" phil-id ") debug: " (apply str args))))

(defn random-from-range
  "Return a random integer from the range-vec [max min]"
  [range-vec]
  (+ (second range-vec) (rand-int (apply - range-vec))))
