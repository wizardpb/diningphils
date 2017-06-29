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
;; core.async
;;
;; See the Chandry-Misra paper (C-M) at https://www.cs.utexas.edu/users/misra/scannedPdf.dir/DrinkingPhil.pdf
;;
;; Philosphers are represented as threads, which hold part of the philospher state as thread-locals.
;;
;; Each is a state machine which implements the main C-M guarded command. State changes are driven by message sends
;; through channels to and from ajoining philosophers. When a philosopher gets hungry, it requests the forks it doesn't
;; have, then begins eating when they arrive. After a while, it becomes sated, and goes back to thinking until it
;; becomes hungry again.
;;
;; Messages between philosophers are sent as evaluable lists of function specifications. These are execute ddirectly
;; by the receiver to trigger state changes.
;;
;; For convenience, message execution is wrapped by a function (execute-message) that places the current state into
;; thread-local vars.
;;
;; 'execute-message' also takes care of calling the main state machine function (state-changed). This implements
;; the C-M guarded command as described above. This returns a new state map that is the new state of the philosopher
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

(ns diningphils.c-m-async.core
  (:require [diningphils.utils :refer :all]
            [diningphils.system :as sys]
            [clojure.string :as str]
            [clojure.core.async :as a]))

(defn initialized-fork
  "Return the initial state of fork fork-id. Forks are numbered such that for philosopher p,
  the left fork id is p, and the right fork id is (mod n (inc p)) where n is the number of philosophers.

  Forks (and request flags) are initialized so that the dependency graph H is acyclic. That is, all forks are dirty,
  and phil-id owns it's left fork (phil-id) except phil 0 owns both forks (0 and 1), and phil 1 owns none. This makes
  the root of H be phil 0"
  [fork-id]
  (->
    (condp = fork-id
      0 {:owner 0 :id 0}
      1 {:owner 0 :id 1}
      {:owner fork-id :id fork-id})
    (assoc :dirty? true))
  )

(defn request-flags-for
  "Return the initial request flags for phil-id. The flags for phil-id p are set opposite fork ownership - that is,
  reqf(p) is false and reqf(p+1) is true, except for phil 0, who has both false, and p 1, who has both true. Flags
  are indexed by 0 for the left fork, and 1 for the right fork"
  [phil-id]
  (condp = phil-id
    0 [false, false]
    1 [true, true]
    [false, true])
  )

(defn initial-phil-state
  "Return the initialized state for philosopher phil-id. This includes the phil-id, forks and request flags for it's
  shared forks. It's neighboring channels will be added after all philosophers are created"
  [phil-id phil-name forks]
  (let [self-chan (a/chan 1)]
    {:phil-id       phil-id
     :phil-name     phil-name
     :state         :starting
     :chans         {:to (a/chan 1) :from (a/chan 1)}                                   ; Channels that I communicate on
     :self          {:from self-chan :to self-chan}                                     ; Channels I receive commands on
     :forks         [(nth forks phil-id) (nth forks (mod (inc phil-id) (count forks)))] ;My forks
     :request-flags (request-flags-for phil-id)})
  )

(defn delay-for [ms fn]
  (future
    (Thread/sleep ms)
    (fn)))

(defn food-left []
  (if (get-in sys/system [:parameters :food-amount])
    (count @(:food-bowl sys/system))
    "unlimited"))

(defn get-food []
  (dosync
    (let [fb (:food-bowl sys/system)
          food (first @fb)]
      (alter fb rest)
      food)))

;; Root bindings for all read-only philosopher thread-local state
(def ^:dynamic *state* nil)               ;; The complete phil state
(def ^:dynamic *phil-name* nil)           ;; Philosopher name
(def ^:dynamic *phil-id* nil)             ;; Philosopher ID - an integer 0 - max-phil-id
(def ^:dynamic *forks* nil)               ;; Shared forks
(def ^:dynamic *neighbors* nil)           ;; Neighboring philosophers (agents)

(defn debug-thread [& args]
  (apply debug-pr *phil-name* *phil-id* args) (flush))

(defn neighbor-index
  "Local fork state for an phil is indexed by 0 for the left resource and 1 for the right. This returns the
  correct index for a fork identified by id"
  [fork-id]
  (let [i (mod (- fork-id *phil-id*) (:phil-count sys/system))]
    (assert (#{0 1} i))
    i))

(defn has-fork? [fork]
  (= *phil-id* (:owner @fork)))

(defn has-fork [fork flag]
  (swap! fork #(assoc % :owner (if flag *phil-id*))))

(defn dirty? [fork]
  (:dirty? @fork))

(defn dirty [fork value]
  (swap! fork #(assoc % :dirty? value)))

(defn has-request? [state fork]
  (nth (:request-flags state) (neighbor-index (:id @fork))))

(defn has-request [state fork value]
  (update-in state [:request-flags (neighbor-index (:id @fork))] (constantly value))
  )

(defn hungry? [state]
  (= :hungry (:state state)))

(defn eating? [state]
  (= :eating (:state state)))

(defn done? [state]
  (= :done (:state state)))

;; State display

(defn forks-owned []
  (let [owned (map #(:id @%) (filter has-fork? *forks*))
        prefix (if (= 1 (count owned)) "fork" "forks")]
    (if (empty? owned)
      "no forks"
      (str prefix " " (str/join " and " (doall (map str owned)))))))

(defn forks-requested [state]
  (let [requested (map #(:id @%) (filter #(has-request? state %) *forks*))
        prefix (if (= 1 (count requested)) ", fork " ", forks ")]
    (if (empty? requested)
      ""
      (str prefix (str/join " and " (doall (map str requested))) " requested")))
  )

(defn show-state [state]
  (let [line-offset 3]
    (show-line 1 "food left:" (food-left))
    (show-line (+ *phil-id* line-offset)
      (str *phil-name* ": " (name (:state state)) ", owns") (str (forks-owned) (forks-requested state))))
  state)

;; Message functions

(declare send-message)

(defn request-fork [state fork]
  (debug-thread "fork " (:id @fork) "requested, dirty=" (dirty? fork))
  (has-request state fork true))

(defn recv-fork [state fork]
  (assert (not (dirty? fork)))
  (debug-thread "receives fork " (:id @fork) ", dirty=" (dirty? fork) )
  (has-fork fork true)
  state)

(defn hungry [state]
  (-> state
    (dissoc :delay)
    (assoc :state :hungry)))

(defn think [state]
  (-> state
    (assoc :delay (delay-for
                    (random-from-range (sys/get-parameter :think-range))
                    #(send-message (:self state) hungry)))
    (assoc :state :thinking)))

(defn eat [state ms]
  (doseq [f *forks*] (dirty f true))
  (-> state
    (assoc :delay (delay-for ms #(send-message (:self state) think)))
    (assoc :state :eating)))

(defn done [state]
  (if-let [delay (:delay state)] (future-cancel delay))
  (-> state
    (dissoc :delay)
    (assoc :state :done)
    (show-state)))

(defn fork-state-change [[state-already-changed state] side-index]
  (let [
        neighbor (nth *neighbors* side-index)
        fork (nth *forks* side-index)
        has-request-flag (has-request? state fork)
        request-fork? (and (hungry? state) has-request-flag (not (has-fork? fork)))
        send-fork? (and (not (eating? state)) has-request-flag (has-fork? fork) (or (done? state) (dirty? fork)))]
    (debug-thread "check state, fork=" (:id @fork) (if (dirty? fork) "dirty" "clean")
      "state=" (:state state)
      "request-fork?="
      request-fork?
      "send-fork?=" send-fork?)
    (let [new-state (cond
                      ;; I'm hungry, don't have a fork and can request one
                      request-fork?
                      (do
                        (debug-thread "requests fork" (:id @fork))
                        (let [s (has-request state fork false)]
                          (send-message neighbor request-fork fork)
                          s))
                      ;; I'm not eating and someone wants a dirty fork
                      send-fork?
                      (do
                        (debug-thread "sends fork" (:id @fork))
                        (assert (has-fork? fork))
                        (dirty fork false)
                        (has-fork fork false)
                        (send-message neighbor recv-fork fork)
                        state)
                      :else state)]
      [(or request-fork? send-fork? state-already-changed) new-state])))

(defn start-eating? [[state-already-changed state]]
  (let [start-eating? (and (hungry? state) (has-fork? (first *forks*)) (has-fork? (last *forks*)))]
    (debug-thread "check state: start-eating?=" start-eating?)
    (let [new-state
          (if start-eating?
            (if-let [food (get-food)]
              (eat state food)
              (done state))
            state)]
      (show-state new-state)
      ;; Return true if we transitioned to done so as to get a chance to service any final fork requests
      [(or (and (not (done? state)) (done? new-state)) state-already-changed) new-state])))

(defn state-change [state]
  ;; loop over the state machine until it tells us not to continue
  ;; Finally return the new state
  (loop [current-state state]
    (let [[continue next-state]
          (-> [false current-state]
            (fork-state-change 0)
            (fork-state-change 1)
            (start-eating?))]
      (if continue
        (recur next-state)
        next-state))))

(defn execute-message [state fn args]
  (binding [*phil-id* (:phil-id state)
            *phil-name* (:phil-name state)
            *forks* (:forks state)
            *neighbors* (:neighbors state)]
    (debug-thread "Executing" (fn-name fn) ", state" (:state state))
    (state-change (apply fn state args))))

(defn send-message [phil-chans fn & args]
  (debug-pr *phil-name* *phil-id* "Sending" (fn-name fn) args "to"
    (condp identical? phil-chans
      (first *neighbors*) (dec *phil-id*)
      (last *neighbors*) (inc *phil-id*)
      "me"
      )
    )
  (a/>!! (:to phil-chans) (cons fn args)))

(defn run-phil [state-atom]
  (future
    (loop [state (execute-message @state-atom think '())]
      (debug-pr (:phil-id state) (:phil-name state) "Reading...")
      (let [chans (conj (mapv :from (:neighbors state)) (->> state :self :from))
            [[fn & args] port] (a/alts!! chans)
            ]
        (when-not (= fn :stop)
          (swap! state-atom #(execute-message %1 fn args))
          (recur @state-atom))))
    ))
