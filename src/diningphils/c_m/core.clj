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
;; Edsger Dijkstra's famous dining philosophers problem, solved using the using Chandy-Misra algorithm and Closure agents
;;
;; See the Chandry-Misra paper (C&M) at https://www.cs.utexas.edu/users/misra/scannedPdf.dir/DrinkingPhil.pdf
;;
;; Philosphers are represented as threads that keep their state in dynamic vars,
;; and communicate via core.async chans. Each is a state machine which implements the main C-M guarded command. State
;; changes are driven by message reception from ajoining philosophers, and by messages from futures that cause
;; transitions between hungry and thinking. When a philosopher gets hungry, it requests the forks it doesn't have,
;; then begins eating when they arrive. After a while, it becomes sated, and goes back to thinking until it becomes
;; hungry again.
;;
;; There is a fixed amount of food, and this goes on until all the food is gone. When a philosopher
;; goes hungry and there is no food left it rests. The system is done when all philosophers are resting.
;;
;; The channels are set up as rendezvous (i.e readers block until something is available,
;; writers block until readers have read the last item sent) by setting the channel buffer size to 1
;;
;; To run with status display, evaluate:
;;
;; (start)
;;
;; It can be stopped at any time with
;;
;; (stop)
;;

(ns diningphils.c-m.core
  (:require
    [diningphils.utils :refer :all]
    [clojure.core.async :as async]
    [clojure.string :as str]))

(def parameters
  {
   :food-amount 20              ;; The amount of food - the total number of eating sessions
   :eat-range [10000 5000]      ;; Max and min for eating durations
   :think-range [10000 5000]    ;; Max and min for thinking durations
  } )

;; These params cause extreme contention on forks - lots of messages
#_(def parameters
  {
   :food-amount 20              ;; The amount of food - the total number of eating sessions
   :eat-range [1000 500]      ;; Max and min for eating durations
   :think-range [1000 500]    ;; Max and min for thinking durations
   } )

;; The names of the dining philosophers. Their position in the vector determines their id
(def philosophers ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"])
(def phil-count (count philosophers))
(def max-phil-id (dec phil-count))
(def food-bowl (atom (:food-amount parameters)))

;; Root bindings for all philosopher thread-local state
(def ^:dynamic *phil-name* nil)           ;; Philosopher name
(def ^:dynamic *phil-id* nil)             ;; Philosopher ID - an integer 0 - max-phil-id
(def ^:dynamic *phil-state* nil)          ;; Philosopher state: :thinkng, :eating, :hungry
(def ^:dynamic *left-fork* nil)           ;; Id of my left-hand fork
(def ^:dynamic *right-fork* nil)           ;; Id of my right-hand fork
(def ^:dynamic *request-flags* [nil nil]) ;; Fork request flags - true if the request has been received
(def ^:dynamic *receive-chans* [nil nil]) ;; Channels to receive fork messages from my neighbors
(def ^:dynamic *request-chans* [nil nil]) ;; Channels to send fork and request messages on
(def ^:dynamic *state-chan* nil)          ;; Local channel to receive state change messages
(def ^:dynamic *cmd-chan* nil)            ;; Channel to receive commands on
(def ^:dynamic *run-flag* nil)            ;; Local flag controlling the state loop

;; Status and control functions
(def cmd-channels
  "A vector of channels on which to receive control commands - one for each philosopher thread"
  (mapv (fn [i] (async/chan 1)) (range phil-count)))

(defn send-command
  "Send a command to philosopher phil-id"
  [phil-id cmd]
  (async/>!! (nth cmd-channels phil-id) {:cmd cmd}))

(defn dump-state-for
  "Send a command to dump the current philosopher state from all philosophers"
  [phil-id]
  (send-command phil-id :dump-state))

(defn dump-state []
  (doall (map #(dump-state-for %) (range phil-count))))

;; Utility funcs
(defn get-food
  "Get some food from the food bowl"
  []
  (swap! food-bowl dec))

(defn food-left? []
  (not (zero? @food-bowl)))

(defn wrapped-phil-id [phil-id]
  (mod phil-id phil-count))

(defn delay-send-on
  "Arrange for a value to be sent on chan in delayMs"
  [chan value delayMs]
  (future
    (Thread/sleep delayMs)
    (async/>!! chan value)))

(defn new-ch-record [to-left from-left to-right from-right]
  {:to-left to-left :from-left from-left :to-right to-right :from-right from-right})

(defn build-channels
  "Build a set of channels for a phil-id. The channels of the left neighbor are passed in from the previous call.
  This is then used recursively to build a vector of channels for all philosophers."
  [phil-id to-left from-left]
  (let [to-right (async/chan 1) from-right (async/chan 1)]
    (if (= phil-id max-phil-id)
      ;; We are done - just return a vector of the last record
      [(new-ch-record to-left from-left to-right from-right)]
      ;; Otherwise defer to the next philosopher, then add this record on the front. Note how the new right neighbor
      ;; channels become the next phils left neighbor channels, with the 'from' becoming the 'to' channels
      (let
        [
         chvec (build-channels (inc phil-id) from-right to-right)
         this-record (if (zero? phil-id)
                       ;; First record - make the whole think circular by swapping 'to' and 'from' from the last record
                       (new-ch-record (:from-right (last chvec)) (:to-right (last chvec)) to-right from-right)
                       ;; Otherwise use the passed in channels of the left neightbor and new ones for the right
                       (new-ch-record to-left from-left to-right from-right))
         ]
        (concat [this-record] chvec)))))

(def channels
  "A vector of channels between philosophers. Indexed by phil-id, each element is a map of from and to channels
  connecting the left and right neighbors"
  (build-channels 0 nil nil))

(def forks
  "A vector of atoms representing each fork - it's current holder and dirty state"
  (mapv
    #(atom
       (if (zero? %)
         ;; All forks are dirty
         ;; Both fork(0) and fork(max-phil-id) are owned by philosopher(max-phil-id),
         ;; otherwise the fork is owned by the same phil-id
         {:owner max-phil-id :dirty? true}
         {:owner % :dirty? true})) (range phil-count)))

(defn nth-fork
  [fork-id]
  (assert (and (>= fork-id 0) (< fork-id (count forks))))
  (nth forks fork-id))

(defn set-fork-dirty
  "Set the fork dirty state for fork fork-id"
  [fork-id state]
  (swap! (nth-fork fork-id) assoc :dirty? state))

(defn set-fork-owner
  "Set the owner of a fork"
  [fork-id phil-id]
  (swap! (nth-fork fork-id) assoc :owner phil-id))

;; Initialization
(defn initialize-forks []
  (alter-var-root #'forks
    (fn [_] (mapv
              #(atom
                 (if (zero? %)
                   ;; All forks are dirty
                   ;; Both fork(0) and fork(max-phil-id) are owned by philosopher(max-phil-id),
                   ;; otherwise the fork is owned by the same phil-id
                   {:owner max-phil-id :dirty? true}
                   {:owner % :dirty? true})) (range phil-count)))))

(defn initial-request-flags-for
  [phil-id]
  ;; Request flags are initialized so the flag for fork(n) is held by the philosopher who doesn't initially hold fork(n)
  (condp = phil-id
    0 [true,true]             ;; Phil(0) holds no forks, so gets both request flags
    max-phil-id [false,false] ;; The reverse is true for phil(max-phil-id)
    [false, true]             ;; Otherwise, phil(n) is holding the leftfork but not the right
  ))

;; State testing
(defn thinking? [] (= *phil-state* :thinking))
(defn eating? [] (= *phil-state* :eating))
(defn hungry? [] (= *phil-state* :hungry))

;; Local state access and update
(defn local-fork-index
  "Return the index of a fork id into any of the thread-local state . Fork-id must be in the correct range"
  [fork-id]
  (assert (or (= *left-fork* fork-id) (= *right-fork* fork-id)))
  (mod (- fork-id *phil-id*) phil-count))

(defn chan-for-fork
  "Return a channel on which to send a fork or request message"
  [fork-id]
  (nth *request-chans* (local-fork-index fork-id)))

(defn holds-request?
  "Returns true if I hold the request token for fork fork-id"
  [fork-id]
  (nth *request-flags* (local-fork-index fork-id)))

;; Fork state testing and update
(defn has-fork?
  "Return true if I have fork fork-id"
  [fork-id]
  (= (:owner @(nth-fork fork-id)) *phil-id*))

(defn dirty?
  "Returns true if fork fork-id is held by *phil-id* and is dirty"
  [fork-id]
  (and (has-fork? fork-id) (:dirty? @(nth-fork fork-id))))

;; State changes
(defn set-fork-request
  "Set the fork request flag state for fork fork-id"
  [fork-id state]
  (set! *request-flags* (assoc *request-flags* (local-fork-index fork-id) state)))

(defn think []
  "Start thinking. Set the new state arrange to go hungry in a random interval"
  (set! *phil-state* :thinking)
  (delay-send-on *state-chan* {:cmd :hungry} (random-from-range (:think-range parameters)))
  )

(defn done []
  "Relax - the food is all gone, and we are done. We won't get hungry anymore, but we can still answer requests for
  forks"
  (set! *phil-state* :resting))

(defn eat []
  "Start eating. Set the new state, and arrange to stop when I'm full"
  (set! *phil-state* :eating)
  (get-food)
  (doseq [fork-id [*left-fork* *right-fork*]] (set-fork-dirty fork-id true))
  (delay-send-on *state-chan* {:cmd :sated} (random-from-range (:eat-range parameters))))

(defn hungry []
  "I'm now hungry. Set the new state"
  (set! *phil-state* :hungry))

;; Status reporting
(defn internal-state
  []
  (str
    "state=" *phil-state*
    ", holds-request?=" [(holds-request? *left-fork*) (holds-request? *right-fork*)]
    ", has-fork?=" [(has-fork? *left-fork*) (has-fork? *right-fork*)]
    ", dirty?=" [(dirty? *left-fork*) (dirty? *right-fork*)]
    ", food-left=" @food-bowl
    ))

;; Philosopher behaviors
(defn state-changed-fork-id
  "Check and act on a state change for fork fork-id. This implements the guarded command described in the C-M paper.
  Return true if we effect another state change"
  [fork-id]
  (let [
         request-fork? (and (hungry?) (holds-request? fork-id) (not (has-fork? fork-id)))
         send-fork? (and (not (eating?)) (holds-request? fork-id) (dirty? fork-id))]
    (debug-pr *phil-name* *phil-id* "check state, fork-id=" fork-id " request-fork?=" request-fork? " send-fork?=" send-fork?)
    (cond
      ;; I'm stopping
      (not *run-flag*) nil
      ;; I'm hungry, don't have a fork and can request one
      request-fork?
      (do
        (debug-pr *phil-name* *phil-id* "requests fork " fork-id)
        (set-fork-request fork-id false)
        (async/>!! (chan-for-fork fork-id) {:cmd :request-fork :fork fork-id}))
      ;; I'm not eating and someone wants a dirty fork
      send-fork?
      (do
        (debug-pr *phil-name* *phil-id* "sends fork " fork-id)
        (assert (has-fork? fork-id))
        (set-fork-dirty fork-id false)
        (set-fork-owner fork-id nil)
        (async/>!! (chan-for-fork fork-id) {:cmd :recv-fork :fork fork-id})))
    (and *run-flag* (or request-fork? send-fork?))))

(defn state-changed
  "The state has changed because of some event (usually a message reception) - examine and act on the change for each
   fork, and continue until no more state changes occur"
  []
  (if (some true? [(state-changed-fork-id *left-fork*) (state-changed-fork-id *right-fork*)])
    (do
      (debug-pr *phil-name* *phil-id* "state change: " (internal-state))
      (state-changed))))

(defn set-next-state
  "Wait for and set the next state. This is determiend by a message from a neighbor or the state change channel"
  []
  (debug-pr *phil-name* *phil-id* "wait for message...")
  (let
    [
      [{cmd :cmd fork-id :fork} port] (async/alts!! (conj *receive-chans* *state-chan* *cmd-chan*))]
    (debug-pr *phil-name* *phil-id* "new cmd: cmd=" cmd ", fork=" fork-id)
    (condp = cmd
      ;; Someones requested a fork from me.
      :request-fork (set-fork-request fork-id true)
      ;; if I now have both forks I can go ahead and eat
      :recv-fork (do
                   (assert (not (dirty? fork-id)))
                   (set-fork-owner fork-id *phil-id*)
                   (if
                     (and (has-fork? *left-fork*) (has-fork? *right-fork*))
                     (do
                       (if (food-left?) (eat) (done)))))
      ;; I'm hungry again, but I might be out of luck ...
      :hungry (if (food-left?) (hungry) (done))
      ;; I'm done eating, and have plenty of energy to resume thinking - but only if there is food left.
      :sated (if (food-left?) (think) (done))
      ;; Send my current state
      :dump-state (debug-pr *phil-name* *phil-id* (internal-state))
      ;; Stop myself
      :stop (set! *run-flag* false)
      (assert false))
      (let [fork-msg (if (nil? fork-id) "" (str ", fork=" fork-id))]
        (debug-pr *phil-name* *phil-id* "message executed: cmd=" cmd fork-msg ", new state=" (internal-state)))
    ))

;; Per-thread bindings for a philosopher
(defn with-bindings-for
  "Set bindings for phil-id then run fn"
  [phil-id fn]
  (binding
    [
      *phil-name* (nth philosophers phil-id)
      *phil-id* phil-id
      *phil-state* nil
      *left-fork* phil-id
      *right-fork* (wrapped-phil-id (inc phil-id))
      *request-flags* (initial-request-flags-for phil-id)
      *receive-chans* [(:from-left (nth channels phil-id)) (:from-right (nth channels phil-id))]
      *request-chans* [(:to-left (nth channels phil-id)) (:to-right (nth channels phil-id))]
      *state-chan* (async/chan 1)
      *cmd-chan* (nth cmd-channels phil-id)
      *run-flag* true]
    (fn)))

(defn forks-held
  []
  (str/join " and "
    (filter #(boolean %)
      (map (fn [fork-id]
             (if (has-fork? fork-id)
               (str "fork(" fork-id "," (if (dirty? fork-id) "dirty)" "clean)"))
               nil)
             ) [*left-fork* *right-fork*]))))

(defn forks-requested
  []
  (str/join " and "
    (filter #(boolean %)
      (map (fn [fork-id]
             (if (holds-request? fork-id)
               (str "fork(" fork-id ")")
               nil)
             ) [*left-fork* *right-fork*]))))

(defn show-state
  "Show my running state."
  []
  ;; Only show if we are not debugging
  (if (not (debugging?))
    (do
      (log (str (line-escape 1) "Food left: " @food-bowl))
      (log
        (line-escape (+ 3 *phil-id*)) *phil-name* "(" *phil-id* "): "
        (str/capitalize (str/join (rest (str *phil-state*))))
        ", holds " (let [s (forks-held)] (if (empty? s) "no forks" s))
        (let [s (forks-requested)] (if (empty? s) "" (str ", has requests for " s)))
        )
      (log (line-escape 8)))))

(defn run-philosopher
  "Core philosopher loop. Initialize, then receive state change messages and act on the new state. Do this until we run
   out of food"
  [phil-id]
  (with-bindings-for phil-id
    (fn []
      (try
        (think) ;; All start off thinking
        (while *run-flag*
          (do
            (show-state)
            (set-next-state)
            (state-changed)))
        (show-state)
        (catch Throwable ex
          (println "Exception in " (nth philosophers *phil-id*) ": " ex))))))

(defn start
  []
  (if (not (debugging?)) (clear-screen))
  (swap! food-bowl (fn [_] (:food-amount parameters)))
  (initialize-forks)
  (doseq
    [phil-id (range phil-count)]
    (future (run-philosopher phil-id))))

(defn stop-phil [phil-id]
  (send-command phil-id :stop))

(defn stop []
  (for [phil-id (range phil-count)] (stop-phil phil-id)))

;(set-debug [])