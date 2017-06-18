(ns diningphils.c-m-agents.core
  (:require [diningphils.utils :refer :all]
            [diningphils.system :as sys]))

(defn initialized-fork
  "Return the initial state of fork fork-id. Forks are numbered such that for philosopher p,
  the left fork id is p, and the right fork id is (mod n (inc p)) where n is the number of philosophers.

  Forks (and request flags) are initialized so that the dependency graph H is acyclic. That is, all forks are dirty,
  and phil-id owns it's left fork (phil-id) except phil 0 owns both forks (0 and 1), and phil 1 owns none. This makes
  the root of H be phil 0"
  [fork-id]
  (->
    (condp = fork-id
      0 {:owner 0}
      1 {:owner 0}
      {:owner fork-id})
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

(defn initial-agent-state
  "Return the initialized state for philosopher phil-id. This includes the phil-id, forks and request flags for it's
  shared forks. It's neighbors will be added after all agents are created"
  [phil-id forks]
  {:phil-id phil-id
   :forks [(nth forks phil-id) (nth forks (mod (inc phil-id) (count forks)))]
   :request-flags (request-flags-for phil-id)}
  )

(defn delay-for [ms fn]
  (future
    (Thread/sleep ms)
    (fn)))

;; Root bindings for all read-only philosopher thread-local state
(def ^:dynamic *state* nil)               ;; The complete agent state
(def ^:dynamic *phil-name* nil)           ;; Philosopher name
(def ^:dynamic *phil-id* nil)             ;; Philosopher ID - an integer 0 - max-phil-id
(def ^:dynamic *phil-count* nil)
(def ^:dynamic *forks* nil)               ;; Shared forks
(def ^:dynamic *neighbors* nil)           ;; Neighboring philosophers (agents)

(defn neighbor-index
  "Local fork state for an agent is indexed by 0 for the left resource and 1 for the right. This returns the
  correct index for a fork identified by id"
  [fork-id]
  (mod (- fork-id *phil-id*) *phil-count*))

(defn fork-for [fork-id]
  (nth *forks* (neighbor-index fork-id)))

(defn neighbor-sharing [fork-id]
  (nth *neighbors* (neighbor-index fork-id)))

(defn has-fork? [fork-id]
  (= *phil-id* (:owner @(fork-for fork-id))))

(defn dirty? [fork-id]
  (:dirty? @(fork-for fork-id)))

(defn has-request? [state fork-id]
  (nth (:request-flags state) (neighbor-index fork-id)))

(defn fork-dirty [fork-id value]
  (swap! (fork-for fork-id) #(assoc % :dirty? value)))

(defn fork-request [state fork-id value]
  (assoc state :request-flags #(assoc % (neighbor-index fork-id) value)))

(defn fork-state-change [[continue state] fork-id]
  )

(defn start-eating? [[continue state]]
  )

(defn state-change [state]
  (-> [false state]
    (fork-state-change 0)
    (fork-state-change 1)
    (start-eating?)))

(defn execute-message [state fn args]
  (binding [*phil-id* (:phil-id state)
            *phil-name* (:phil-name state)
            *phil-count* (:phil-count state)
            *forks* (:forks state)
            *neighbors* (:neighbors state)]
    (loop [[changed state] (state-change (apply fn state args))]
      (if changed
        (recur (state-change state))
        state))))

(defn send-message [agent fn & args]
  (apply send-off execute-message fn args))

(defn hungry [state]
  (state-change (assoc state :state :hungry)))

(defn think [state]
  (state-change (assoc state :state :thinking))
  (delay-for (random-from-range (sys/get-parameter :think-range)) #(hungry state)))

(defn eat [state ms]
  (state-change (assoc state :state :eating))
  (delay-for ms #(think state)))

(defn run-phil [sys phil-id]
  (Thread/sleep (random-from-range [1 10]))
  (send-message (nth (:agents sys) phil-id) hungry))