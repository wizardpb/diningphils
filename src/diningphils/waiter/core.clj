(ns diningphils.waiter.core
  (:require [clojure.core.async :as a])
  (:use [diningphils.utils]))

(def parameters
  {
   :food-amount 10
   :eat-range [10000 2000]
   :think-range [10000 2000]
   } )

(def phil-names
  "Our philosopher names"
  ["Aristotle" "Kant" "Spinoza" "Marx" "Russell"])

;; The food to eat. This is decremented until it reaches 0, when things stop
;; A value of nil allows running indefinitely
(def food-bowl)

;; Connections to and from the waiter
(def to-chans)
(def from-chans)

;; Reverse mapping between waiter receive channels and philosopher index
(def chan-indices)

;; Waiter fork states - non-nil in fork[i] indicates possesion by philosopher i
;; Waiter fork requests - non-nil in fork-requests[i] indicates a request by philosopher i
(def forks (vec (repeatedly (count phil-names) (partial atom nil))))
(def fork-requests (vec (repeatedly (count phil-names) (partial atom nil))))

(defn initialize [p]
  (alter-var-root #'food-bowl (fn [_] (atom (:food-amount p))))
  (alter-var-root #'to-chans (fn [_] (vec (repeatedly (count phil-names) (partial a/chan 1)))))
  (alter-var-root #'from-chans (fn [_] (vec (repeatedly (count phil-names) (partial a/chan 1)))))
  (alter-var-root #'chan-indices (fn [_] (into {} (map-indexed #(vector %2 %1)) to-chans))))

(defn allocate-fork [fork-id phil-id]
  (let [fork (nth forks fork-id)
        request (nth fork-requests fork-id)]
    (debug-pr "allocate " fork-id "fork: " fork "request: " request)
    (if @fork
      ;; Fork is allocated - set a request
      (do
        (swap! request (fn [_] phil-id))
        (debug-pr "fork allocated, request: " request))
      ;; Otherwise send the fork
      (do
        (swap! fork (fn [_] phil-id))
        (debug-pr "sending fork, request: " request)
        (a/>!! (nth from-chans phil-id) fork-id)))))

(defn free-fork [fork-id phil-id]
  (let [fork (nth forks fork-id)
        request (nth fork-requests fork-id)]
    (debug-pr "free " fork-id "fork: " fork "request: " request)
    (swap! fork (fn [_] nil))
    (if-let [requester @request]
      (do
        (swap! request (fn [_] nil))
        (swap! fork (fn [_] requester))
        (a/>!! (nth from-chans requester) fork-id)))))

(defn dispatch [msg phil-id]
  (debug-pr "Dispatch " msg " from " phil-id)
  (when (not= msg :end)
    (eval (concat msg (list phil-id)))
    true))

(defn say [msg phil-id]
  (println (nth phil-names phil-id) (str "says \"" msg "\"")))

(defn request-forks [left right phil-id]
  (debug-pr phil-id " requests " left " " right)
  (doseq [f [left right]] (allocate-fork f phil-id)))

(defn free-forks [left right phil-id]
  (debug-pr phil-id " frees " left " " right)
  (doseq [f [left right]] (free-fork f phil-id) ))

(defn send-food [phil-id]
  ;; Return true if there is food left (> 0 @food-bowl) or there is unlimited food (nil? @food-bowl).
  ;; If there is food, remove a helping
  (a/>!! (nth from-chans phil-id)
    (let [food @food-bowl
         food-left (and food (> food 0))]
     (when food-left (swap! food-bowl dec))
     (or (nil? food) food-left))))

(defn is-food-left? [phil-id]
  (a/>!! (nth from-chans phil-id) (or (nil? @food-bowl) (> @food-bowl 0))))

(defn run-waiter []
  (let [[v c] (a/alts!! to-chans)]
    (if (dispatch v (get chan-indices c))
      (recur))))

; Philosoper state fns

(def ^:dynamic *phil-id*)
(def ^:dynamic *phil-name*)
(def ^:dynamic *to-chan*)
(def ^:dynamic *from-chan*)
(def ^:dynamic *left-fork*)
(def ^:dynamic *right-fork*)

(defn show-state [& args]
  (show-line 1 "food left: " (if-let [f @food-bowl] f "unlimited"))
  (apply show-line (concat (list (+ *phil-id* 3) (str *phil-name* ": ")) args)) (flush)
  ;(debug-pr *phil-id* (str *phil-name* ":") args "\n")
  )

(defn send-request [& args]
  (a/>!! *to-chan* args))

(defn ask-waiter [& args]
  (a/>!! *to-chan* args)
  (a/<!! *from-chan*))

(defn think []
  (when (ask-waiter 'is-food-left?)
    (show-state "thinking...")
    (Thread/sleep (random-from-range (:think-range parameters)))))

(defn eat []
  (show-state "eating with forks " *left-fork* " and " *right-fork* )
  (Thread/sleep (random-from-range (:eat-range parameters)))
  (send-request 'free-forks *left-fork* *right-fork*))

(defn wait-fork []
  (let [recv-fork (a/<!! *from-chan*)]
    (condp = recv-fork
     *left-fork* (show-state "hungry, has fork " *left-fork* " (left)")
     *right-fork* (show-state "hungry, has fork " *right-fork* " (right)")
     )))

(defn get-forks []
  (show-state "hungry, requests forks " *left-fork* " and " *right-fork*)
  (send-request 'request-forks *left-fork* *right-fork*)
  (wait-fork)
  (wait-fork))

(defn run-phil [phil-id]
  (binding [*phil-id* phil-id
            *phil-name* (nth phil-names phil-id)
            *left-fork* phil-id
            *right-fork* (mod (inc phil-id) (count phil-names))
            *to-chan* (nth to-chans phil-id)
            *from-chan* (nth from-chans phil-id)]
    (loop []
      (think)
      ;; We're hungry - if there is food left, get forks and eat
      (if (ask-waiter 'send-food)
        (do
          (get-forks)
          (eat)
          (recur))
        (show-state "Done.")))))

(def waiter nil)
(def philosophers [])

(defn start
  ([p]
   (initialize (merge parameters p))
   (alter-var-root #'waiter (fn [_] (future (run-waiter))))
   (alter-var-root #'philosophers (fn [_] (vec (map #(future (run-phil %1)) (range (count phil-names))))))
   (clear-screen)
   (future
     (doseq [phil philosophers] @phil)
     (future-cancel waiter)
     (show-line (+ (count philosophers) 6) "Finished"))
   (Thread/sleep 500)
   (show-line (+ (count philosophers) 6) ""))
  ([] (start {})))

(defn stop []
  (doseq [pf philosophers] (future-cancel pf)))
