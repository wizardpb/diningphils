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
;; Edsger Dijkstra's famous dining philosophers problem, solved using a separate Wiater process to allocate forks
;;
;; Common infrastucture for the 4 solutions to teh Dining Philosophers problem. These fuctions initialize, start
;; and stop the system, built around a system map containing global state and run-time parameters
;;

(ns diningphils.system
  (:require [clojure.core.async :as a])
  (:use [diningphils.utils]))

(def system)

(defn init
  "Constructs the current development system."
  [sys-fn]
  (alter-var-root #'system
    (constantly (sys-fn))))

(defn start
  "Starts the current development system."
  [start-fn]
  (alter-var-root #'system start-fn))

(defn stop
  "Shuts down and destroys the current development system."
  [stop-fn]
  (alter-var-root #'system
    (fn [s] (when s (stop-fn s)))))

(defn wait-for-done [clean-fn stop-fn]
  (let [phils (:phils system)
        end-ch (a/thread
                 (clean-fn system)
                 "Finished")
        stop-ch (a/thread
                  (show-line (+ (count phils) 6) "Press return to stop")
                  (read-line)
                  (stop stop-fn)
                  "Stopped")
        [val _] (a/alts!! [end-ch stop-ch])
        ;[val _] (a/alts!! [end-ch])
        ]
    (show-line (+ (count phils) 6) val "\n")
    'Done))
