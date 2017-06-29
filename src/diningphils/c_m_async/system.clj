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

(ns diningphils.c-m-async.system
  (:require [diningphils.system :as sys]
            [clojure.core.async :as a])
  (:use [diningphils.utils]
        [diningphils.c-m-async.core])
  (:import (java.util.concurrent CancellationException)))

(defn connect [state phil-states]
  ; Channels are labeled :to and :from with respect to the sending or receiving philosoper i.e they send on the :to
  ; channels and receive on the :from. Thus, the :to channel on my neighbor becomes the :from channel for me.
  (let [n (count phil-states)
        id (:phil-id state)
        left (:chans (nth phil-states id))                  ; Channels for my left neighbor are my channels
        right (:chans (nth phil-states (mod (inc id) n)))   ; Channels for my right neighbor are their channels
        neighbors [
                   {:from (:from left) :to (:to left)}
                   {:to (:from right) :from (:to right)}]
        ]
    (assoc state :neighbors neighbors)))

(defn init-fn [p]
  (let [phil-names ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"]
        phil-count (count phil-names)
        base-params {:food-amount 10 :eat-range [10000 2000] :think-range [10000 2000]}
        params (merge base-params p)
        forks (mapv #(atom (initialized-fork %)) (range phil-count))]
    {
     :parameters  params
     :phil-names  phil-names
     :phil-count  phil-count
     :food-bowl   (ref (if-let [f (:food-amount params)]
                         (repeatedly f (partial random-from-range (:eat-range params)))
                         (repeatedly (partial random-from-range (:eat-range params)))))
     :forks       forks
     :phil-states (let [phil-states (map-indexed #(initial-phil-state %1 %2 forks) phil-names)]
                    (mapv #(atom (connect %1 %2)) phil-states (repeat phil-states)))
     }
    ))

(defn start-fn [sys]
  (clear-screen)
  (let [phil-states (:phil-states sys)]
    (assoc sys :phils (mapv #(run-phil %) phil-states))))

(defn monitor [sys]
  (let [states (:phil-states sys)]
    (while (not (every? #(= :done (:state (deref %))) states))
      (Thread/sleep 1000))
    (doseq [state-atom states]
      (a/>!! (->> @state-atom :self :to) [:stop]))))

(defn clean-fn [sys]
  (let [mf (future-call #(monitor sys))]
    (doseq [phil (:phils sys)]
      (try (deref phil) (catch CancellationException e)))
    @mf))

(defn stop-fn [sys]
  (doseq [phil (:phils sys)]
    (future-cancel phil))
  sys
  )

(defn init
  ([p] (sys/init (partial init-fn p)))
  ([] (sys/init (partial init-fn {}))))

(defn start []
  (sys/start start-fn))

(defn stop []
  (sys/stop stop-fn))

(defn go
  ([p]
   (init p)
   (start)
   (sys/wait-for-done clean-fn stop-fn))
  ([] (go {})))

