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
;; agents
;;
;; See the Chandry-Misra paper (C-M) at https://www.cs.utexas.edu/users/misra/scannedPdf.dir/DrinkingPhil.pdf
;;
;; Philosphers are represented as agents, which hold the philospher state.
;;
;; Each is a state machine which implements the main C-M guarded command. State changes are driven by message sends
;; from ajoining philosophers. When a philosopher gets hungry, it requests the forks it doesn't have, then begins
;; eating when they arrive. After a while, it becomes sated, and goes back to thinking until it becomes hungry again.
;;
;; Messages between philosophers (agents) are implemented as agent sends (and sent using send-off because of state
;; monitoring). To save having to pass a new state map around between various functions that access and manipulate it,
;; message execution is wrapped by a function (execute-message) that places the current state into thread-local vars.
;; This includes the var *state*, which holds the new state to be set on message completion. Any function that
;; updates the state places the new state map into this var, which is finally returned as the new state for the agent.
;;
;; For brevity, other read-only values from the agent state (such as the id's of adjoining philosopher agents,
;; needed for message sending) are also placed into thread-local vars.
;;
;; 'execute-message' also takes care of calling the main state machine function (state-changed). This implements
;; the C-M guarded command as described above.
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
;; (start)
;;
;; It can be stopped at any time with
;;
;; (stop)
;;

(ns diningphils.c-m-agents.core
  (:require
    [diningphils.utils :refer :all]
    [clojure.string :as str]))

(def parameters
  {
    :food-amount 20              ;; The amount of food - the total number of eating sessions
    :eat-range [10000 5000]      ;; Max and min for eating durations
    :think-range [10000 5000]    ;; Max and min for thinking durations
    } )

;; This set of parameters makes things go much faster :-)
;(def parameters
;  {
;   :food-amount 20              ;; The amount of food - the total number of eating sessions
;   :eat-range [1000 500]      ;; Max and min for eating durations
;   :think-range [1000 500]    ;; Max and min for thinking durations
;  } )

;; The names of the dining philosophers. Their position in the vector determines their id
(def philosophers ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"])
(def phil-count (count philosophers))
(def max-phil-id (dec phil-count))
(def food-bowl (atom (:food-amount parameters)))
(def max-name-size (apply max (map count philosophers)))

(declare phil-agents)

;; Root bindings for all read-only philosopher thread-local state
(def ^:dynamic *state* nil)               ;; The complete agent state
(def ^:dynamic *phil-name* "Unknown")     ;; Philosopher name
(def ^:dynamic *phil-id* nil)             ;; Philosopher ID - an integer 0 - max-phil-id
(def ^:dynamic *left-fork-id* nil)           ;; Id of my left-hand fork
(def ^:dynamic *right-fork-id* nil)          ;; Id of my right-hand fork
(def ^:dynamic *right-phil-id* nil)          ;; Id of my right-hand neighbor
(def ^:dynamic *left-phil-id* nil)           ;; Id of my left-hand neighbor

;; Utility funcs
(defn get-food
  "Get some food from the food bowl"
  []
  (swap! food-bowl dec))

(defn food-left? []
  "True if there is food left"
  (not (zero? @food-bowl)))

(defn wrapped-id [id]
  "Return a fork or philosopher id that wraps around the table"
  (mod id phil-count))

(def forks
  "A vector of atoms representing each fork - it's current holder and dirty state"
  (vec (map
         #(atom
           (if (zero? %)
             ;; All forks are dirty
             ;; Both fork(0) and fork(max-phil-id) are owned by philosopher(max-phil-id),
             ;; otherwise the fork is owned by the same phil-id
             {:owner max-phil-id :dirty? true}
             {:owner % :dirty? true})) (range phil-count))))

(defn nth-fork
  [fork-id]
  (assert (and (>= fork-id 0) (< fork-id (count forks))))
  (nth forks fork-id))

(defn set-fork-dirty
  "Set the fork dirty state for fork fork-id"
  [fork-id fork-state]
  (swap! (nth-fork fork-id) assoc :dirty? fork-state))

(defn set-fork-owner
  "Set the owner of a fork"
  [fork-id phil-id]
  (swap! (nth-fork fork-id) assoc :owner phil-id))

;; Initialization
(defn initial-request-flags-for
  "Return a set of request flags for phil-id. The flags array is indexed by fork id, and since a philosopher is only
  interested in two fork, unused elements are set to nil"
  [phil-id]
  ;; Request flags are initialized so the flag for fork(n) is held by the philosopher who doesn't initially hold fork(n)
  (condp = phil-id
    0 [true,true,nil,nil,nil]             ;; Phil(0) holds no forks, so gets both request flags
    max-phil-id [false,nil,nil,nil,false] ;; The reverse is true for phil(max-phil-id)
    (vec (concat
      (repeat phil-id nil)
      [false, true]                       ;; Otherwise, phil(n) is holding the leftfork but not the right
      (repeat (- max-phil-id (+ phil-count 2)) nil)))
  ))

(defn initial-agent-state
  "Initialize the state of a philosopher agent. All start of thinking"
  [phil-id]
  {
    :phil-name (nth philosophers phil-id)
    :phil-id phil-id
    :phil-state nil
    :forks {
             :left {:id phil-id :neighbor (wrapped-id (dec phil-id))}
             :right {:id (wrapped-id (inc phil-id)) :neighbor (wrapped-id (inc phil-id))}
             }
    :request-flags (initial-request-flags-for phil-id)
   })

;; State testing
(defn thinking? [] (= (:phil-state *state*) :thinking))
(defn eating? [] (= (:phil-state *state*) :eating))
(defn hungry? [] (= (:phil-state *state*) :hungry))
(defn done? [] (= (:phil-state *state*) :done))

;; Local state access
(defn holds-request?
  "Returns true if I hold the request token for fork fork-id"
  [fork-id]
  (nth (:request-flags *state*) fork-id))

(defn has-fork?
  "Return true if I have fork fork-id"
  [fork-id]
  (= (:owner @(nth-fork fork-id)) *phil-id*))

(defn dirty?
  "Returns true if fork fork-id is held by *phil-id* and is dirty"
  [fork-id]
  (and (has-fork? fork-id) (:dirty? @(nth-fork fork-id))))

;; Message sending

(declare state-changed)

(defn execute-message
  "Execute an agent message, binding a local state var, and responding to any new state set"
  [state fn & args]
  (binding
    [*state* state
     *phil-name* (:phil-name state)
     *phil-id* (:phil-id state)
     *left-fork-id* (get-in state [:forks :left :id])
     *right-fork-id* (get-in state [:forks :right :id])
     *left-phil-id* (get-in state [:forks :left :neighbor])
     *right-phil-id* (get-in state [:forks :right :neighbor])
     ]
    (debug-pr *phil-name* *phil-id* "Executing " fn " with (" args ")")
    (state-changed (apply fn args))
    ))

(defn send-message
  [a fn & args]
  (if (agent-error a)
    (println "Agent error:" (agent-error a))
    (do
      (debug-pr (:phil-name @a) (:phil-id @a) "sends " fn "(" args ") to " (:phil-name @a) "(" (:phil-id @a) ")")
      (apply send-off a execute-message fn args))))

(defn delay-send-on
  "Arrange for the message (fn args) to be sent to agent after delay mS"
  [a delayMs fn & args]
  (debug-pr (:phil-name @a) (:phil-id @a) "will send " fn " to " a " in " delayMs)
  (future
    (Thread/sleep delayMs)
    (apply send-message a fn args)))

;; State change
(defn set-fork-request
  "Set the fork request flag state for fork fork-id"
  [state fork-id fork-state]
  (assoc-in state [:request-flags fork-id] fork-state))

(defn hungry []
  "I'm now hungry. Set the new state"
  (assoc *state* :phil-state
    (if (and (not (done?)) (food-left?)) :hungry :done)))

(defn think []
  "Start thinking. Set the new state arrange to go hungry in a random interval"
  (if (and (not (done?)) (food-left?))
    (do
      (delay-send-on *agent* (random-from-range (:think-range parameters)) hungry )
      (assoc *state* :phil-state :thinking))
    (assoc *state* :phil-state :done)))

(defn done []
  "Relax - the food is all gone, and we are done. We won't get hungry anymore, but we can still answer requests for
  forks"
  (assoc *state* :phil-state :done))

(defn eat []
  "Start eating. Get some food, set the new state, and arrange to stop when I'm full"
  (get-food)
  (doseq [fork-id [*left-fork-id* *right-fork-id*]] (set-fork-dirty fork-id true))
  (delay-send-on *agent* (random-from-range (:eat-range parameters)) think)
  (assoc *state* :phil-state :eating))


;; Status reporting
(defn internal-state
  []
  (str
    "state=" (:phil-state *state*)
    ", holds-request?=" [(holds-request? *left-fork-id*) (holds-request? *right-fork-id*)]
    ", has-fork?=" [(has-fork? *left-fork-id*) (has-fork? *right-fork-id*)]
    ", dirty?=" [(dirty? *left-fork-id*) (dirty? *right-fork-id*)]
    ", food-left=" @food-bowl
    ))

;; Philosopher behaviors
(defn request-fork
  "Receive a fork request from a neighbor. Set the new state and return it."
  [fork-id]
  (set-fork-request *state* fork-id true))

(defn recv-fork
  "Receive a requested fork from a neighbor. Set the new fork state and return the current agent state."
  [fork-id]
  (assert (not (dirty? fork-id)))
  (set-fork-owner fork-id *phil-id*)
  *state*)

(defn now-eating?
  "Check if we are now able to eat i.e. we are hungry and now have both forks. If so,
  set the new state. Return true if we have changed state to show we are now eating."
  []
  (let [start-eating? (and (hungry?) (has-fork? *left-fork-id*) (has-fork? *right-fork-id*))]
    (debug-pr *phil-name* *phil-id* "check state: start-eating?=" start-eating?)
    (if start-eating? (set! *state* (if (food-left?) (eat) (done))))
    start-eating?))

(defn state-changed-side?
  "Check and act on a state change for fork fork-id. This implements the guarded command described in the C-M paper.
  Return true if we effect another state change"
  [fork-id neighbor-id]
  (let [
         neighbor (nth phil-agents neighbor-id)
         request-fork? (and (hungry?) (holds-request? fork-id) (not (has-fork? fork-id)))
         send-fork? (and (not (eating?)) (holds-request? fork-id) (dirty? fork-id))]
    (debug-pr *phil-name* *phil-id* "check state, fork-id=" fork-id " request-fork?=" request-fork? " send-fork?=" send-fork?)
    (cond
      ;; I'm hungry, don't have a fork and can request one
      request-fork?
      (do
        (debug-pr *phil-name* *phil-id* "requests fork " fork-id)
        (set! *state* (set-fork-request *state* fork-id false))
        (send-message neighbor request-fork fork-id))
      ;; I'm not eating and someone wants a dirty fork
      send-fork?
      (do
        (debug-pr *phil-name* *phil-id* "sends fork " fork-id)
        (assert (has-fork? fork-id))
        (set-fork-dirty fork-id false)
        (set-fork-owner fork-id nil)
        (send-message neighbor recv-fork fork-id)))
    (and (not (done?)) (or request-fork? send-fork?))))

(defn forks-held
  []
  (str/join " and "
    (filter #(boolean %)
      (map (fn [fork-id]
             (if (has-fork? fork-id)
               (str "fork(" fork-id "," (if (dirty? fork-id) "dirty)" "clean)"))
               nil)
             ) [*left-fork-id* *right-fork-id*]))))

(defn forks-requested
  []
  (str/join " and "
    (filter #(boolean %)
      (map (fn [fork-id]
             (if (holds-request? fork-id)
               (str "fork(" fork-id ")")
               nil)
             ) [*left-fork-id* *right-fork-id*]))))

(defn show-state
  "Show my running state."
  []
  ;; Only show if we are not debugging
  (if (not (debugging?))
    (let [name-padding (apply str (repeat (- max-name-size (count *phil-name*)) " "))]
      (log (str (line-escape 1) "Food left: " @food-bowl))
      (log
        (line-escape (+ 3 *phil-id*)) name-padding " " *phil-name* "(" *phil-id* "): "
        (str/capitalize (str/join (rest (str (:phil-state *state*)))))
        ", holds " (let [s (forks-held)] (if (empty? s) "no forks" s))
        (let [s (forks-requested)] (if (empty? s) "" (str ", has requests for " s)))
        )
      (log (line-escape 8)))))

(defn state-changed
  "The state has changed because of some event (usually a message reception) - examine and act on the change - once for
  each fork change and also if we have started eating. This is called recursively until no state change occurs."
  [new-state]
  (set! *state* new-state)
  (show-state)
  (debug-pr *phil-name* *phil-id* "state change: " (internal-state))
  (let [state-changed-vec
        [
          (state-changed-side? *left-fork-id* *left-phil-id*)
          (state-changed-side? *right-fork-id* *right-phil-id*)
          (now-eating?)
          ]
        ]
    (if (some true? state-changed-vec)
      (state-changed *state*)   ;; Continue while the state has changed
      *state*)))                ;; Otherwise return the new state

;; Debiug helper
(defn dump-state
  []
  (log (internal-state))
  *state*)

(def phil-agents
  (map
    #(agent
       (initial-agent-state %)
       :error-handler (fn [a exception] (log (:phil-name @a) "(" (:phil-id @a) ") throws exception" exception ))
       :error-mode :fail)
    (range phil-count)))

(defn start-philosopher
  "Start a philosopher agent by setting an initial state, and arranging for it to start thinking"
  [phil-id]
  (let [a (nth phil-agents phil-id)]
    (debug-pr (:phil-name @a) phil-id "starting...")
    (send-message a think)))

(defn start
  "Start up each agent. Initialize the food bowl and tell philosophers to start"
  []
  (if (not (debugging?)) (clear-screen))
  (swap! food-bowl (fn [_] (:food-amount parameters)))
  (map (fn [phil-id]
         (start-philosopher phil-id)
         phil-id) (range phil-count)))

(defn stop
  []
  (map #(send-message (nth phil-agents %) done) (range phil-count)))

;(set-debug (range phil-count))
(set-debug [])