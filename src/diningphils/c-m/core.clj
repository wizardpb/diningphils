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
;; Each philosopher is represented as a thread and a state map. The thread repeatedly takes forks, eats, frees forks and thinks
;; until all the food is gone. The state map holds the eating state, the forks to the left and right of the philosopher,
;; and a food amount.
;;
;;
;; Edsger Dijkstra's famous dining philosophers problem, solved using the using Chandy-Misra algorithm and Closure agents
;;
;; See the Chandry-Misra paper (C&M) at https://www.cs.utexas.edu/users/misra/scannedPdf.dir/DrinkingPhil.pdf
;;
;; Philosphers are represented as threads that keep their state in a dynamic var,
;; and communicate via core.async chans. Each is a state machine which implements the main C-M guarded command. State
;; changes are driven by message reception from ajoining philosophers, and by messages from futures that cause
;; transitions between hungry and sated.
;;
;; The channels are set up as rendezvous (i.e readers block until something is available,
;; writers block until readers have read the last item sent) by setting the channel buffer size to 1
;;
;; To run with status display, evaluate:
;;
;; (start)
;;

(ns diningphils.c-m.core
  (:require [clojure.core.async :as async]))

(def parameters
  {
   :food-amount 10
   :max-eat-duration 2000
   :max-think-duration 2000
  } )

;; The names of the dining philosophers. Their position in the vector determines their id
(def philosophers ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"])
(def phil-count (count philosophers))
(def max-phil-id (dec phil-count))

(defn wrapped-phil-id [phil-id]
  (mod phil-id phil-count))

(defn future-value-on
  "Arrange for a value to be sent on chan in delayMs"
  [chan value delayMs]
  (future
    (Thread/sleep delayMs)
    (async/>!! chan value)))

(def channels
  "A map of channels between philosophers, split into from and to,and indexed by phil-id."
  {:from (vec (repeat max-phil-id (async/chan 1))) :to (vec (repeat max-phil-id (async/chan 1)))})

(defn chan-from
  "Get the channel to receive things from phil-id on"
  [phil-id]
  (assert (and (>= phil-id 0) (< phil-id max-phil-id)))
  (let [{ {fchan phil-id} :from} channels] fchan))    ;; Structural binding is an easy way to extract the channel

(defn chan-to
  "Get the channel to send things to phil-id on"
  [phil-id]
  (assert (and (>= phil-id 0) (< phil-id max-phil-id)))
  (let [{ {tchan phil-id} :to} channels] tchan))

;; Root bindings for all philosopher thread-local state
(def ^:dynamic *phil-id* nil)             ;; Philosopher ID - an integer 0 - max-phil-id
(def ^:dynamic *phil-state* nil)          ;; Philosopher state: :thinkng, :eating, :hungry
(def ^:dynamic *food-left* nil)           ;; The amount of food left. Everything stops when this = 0
(def ^:dynamic *left-fork* nil)           ;; Id of my left-hand fork
(def ^:dynamic *right-fork* nil)           ;; Id of my right-hand fork
(def ^:dynamic *fork-flags* [nil nil])    ;; Fork flags - true if the philosopher has the fork
(def ^:dynamic *request-flags* [nil nil]) ;; Fork request flags - true if the request has been received
(def ^:dynamic *dirty-flags* [nil nil])   ;; Dirty flag - true if the possesed fork is dirty
(def ^:dynamic *receive-chans* [nil nil]) ;; Channels to receive fork messages from my neighbors
(def ^:dynamic *request-chans* [nil nil]) ;; Channels to send fork and request messages on
(def ^:dynamic *state-chan* nil)          ;; Local channel to receive state change messages

;; All of these functions can *only* be called from the philospher thread, as they reference/use thread-local state

(defn local-fork-index
  "Return the index of a fork id into any of the thread-local state . Fork-id must be in the correct range"
  [fork-id]
  (assert fork-id (or (= (*left-fork*)) (= *right-fork*)))
  (- fork-id *phil-id*))

(defn chan-for-fork
  "Return a channel to send a fork and request on"
  [fork-id]
  (nth *request-chans* (local-fork-index fork-id)))

;; State testing
(defn thinking? [] (= *phil-state* :thinking))
(defn eating? [] (= *phil-state* :eating))
(defn hungry? [] (= *phil-state* :hungry))

(defn has-fork?
  "Return true if I have fork fork-id"
  [fork-id]
  (nth *fork-flags* (local-fork-index fork-id)))

(defn holds-request?
  "Returns true if I hold the request token for fork fork-id"
  [fork-id]
  (nth *request-flags* (local-fork-index fork-id)))

(defn dirty?
  "Returns true if fork fork-id is dirty"
  [fork-id]
  (nth *dirty-flags* (local-fork-index fork-id)))

;; State changes
(defn has-fork
  "Set the fork holding state for fork fork-id"
  [fork-id state]
  (set! *fork-flags* (assoc *fork-flags* (local-fork-index fork-id) state)))

(defn has-request
  "Set the fork request flag state for fork fork-id"
  [fork-id state]
  (set! *request-flags* (assoc *request-flags* (local-fork-index fork-id) state)))

(defn dirty
  "Set the fork dirty state for fork fork-id"
  [fork-id state]
  (set! *dirty-flags* (assoc *dirty-flags* (local-fork-index fork-id) state)))

(defn think []
  "Start thinking. Set the new state arrange to go hungry in a random interval"
  (set! *phil-state* :thinking)
  (future-value-on *state-chan* {:cmd :go-hungry} (rand-int (:max-think-duration parameters))))

(defn eat []
  "Start eating. Set the new state, and arrange to stop when I'm full"
  (set! *phil-state* :eating)
  (doseq [fork-id [*left-fork* *right-fork*]] (dirty fork-id true))
  (future-value-on *state-chan* {:cmd :sated} (rand-int (:max-eat-duration parameters)))
  (dec *food-left*))

(defn hunger []
  "I'm now hungry. Set the new state"
  (set! *phil-state* :hungry))

;; Initialization

(defn initial-forks
  [phil-id]
  )

(defn initial-requests
  [phil-id]
  )

(defn receive-chans-for
  [phil-id]
  )

(defn request-chans-for
  [phil-id]
  )

;; Philosopher behaviors
(defn state-changed
  "The state has changed because of some event (usually a message reception) - examine the new state and act
  accordingly. Implements the guarded command central to the C-M paper."
  []
  (doseq [fork-id [*left-fork* *right-fork*]]
    (cond
      ;; I'm hungry, don't have a fork and can request one
      (and (hungry?) (holds-request? fork-id) (not (has-fork? fork-id)))
      (do
        (async/>!! (chan-for-fork fork-id) {:cmd :request-fork :fork fork-id})
        (has-request fork-id false))
      ;; I'm not eating and someone wants a dirty fork
      (and (not (eating?)) (holds-request? fork-id) (dirty? fork-id))
      (do
        (async/>!! (chan-for-fork fork-id {:cmd :send-fork :fork fork-id}))
        (dirty fork-id false)
        (has-fork fork-id false)))))

(defn set-next-state
  "Wait for and set the next state. This is determiend by a message from a neightbor or the state change channel"
  []
  (let [{cmd :cmd fork-id :fork} (async/>!! (conj *receive-chans* *state-chan*))]
    (condp = cmd
      :request-fork (has-request fork-id true)
      :send-fork (do
                   (has-fork fork-id true)
                   (if
                     (and (has-fork? *left-fork*) (has-fork? *right-fork*))
                     (set! *food-left* (eat))))
      :go-hungry (hunger)
      :sated (think)
      ())))

;; Core philosopher loop
(defn run-philosopher
  "Core philosopher loop. Inialize, then receive state change messages and act on the new state. Do this until we run
   out of food"
  [phil-id]
  (binding
    [
      *phil-id* phil-id
      *phil-state* :thinking
      *food-left* (:food-amount parameters)
      *left-fork* phil-id
      *right-fork* (mod (inc phil-id) phil-count)
      *fork-flags* (initial-forks phil-id)
      *request-flags* (initial-requests phil-id)
      *dirty-flags* [true true]
      *receive-chans* (receive-chans-for phil-id)
      *request-chans* (request-chans-for phil-id)
      *state-chan* (async/chan 1)]
    (while (> *food-left* 0)
      (set-next-state)
      (state-changed))))


