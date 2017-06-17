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
;; Edsger Dijkstra's famous dining philosophers problem, solved using a separate 'Waiter' process to allocate forks
;;
;; Each philosoher is represented as a thread (a future) that repeated gets hungry, asks the wait for
;; forks and food, eats and thinks. The waiter prevents deadlock by synchronizing the allocation of forks for
;; each philosopher. For each fork, it tracks ownership and requests. Since each fork is shared between two
;; philosophers, the fork is either free or owned by one philosopher, with the other philosopher possible requesting
;; it. This state can therefore be represented with two atoms per fork, one holding the owner and one holding a
;; requester. A nil value in the owner atom indicates the fork is free.
;;
;; The waiter is connected to each philosopher by two core.async channels, over which it receives requests and provides
;; resources. Requests are sent by the philosophers in the form of lists which are treated as literal representation
;; of function calls. Implenting these functions provides the sent of requests the waiter understands
;;
;; To run with status display, evaluate:
;;
;; (go)
;;
;; from the diningphils.waiter.system namespace. Hitting any key will stop the simulation.
;;

(ns diningphils.waiter.core
  (:require [clojure.core.async :as a]
            [diningphils.system :as sys])
  (:use [diningphils.utils]))

(defn allocate-fork [sys fork-id phil-id]
  (let [fork (nth (:forks sys) fork-id)
        request (nth (:fork-requests sys) fork-id)]
    ;(debug-pr "allocate " fork-id "fork: " fork "request: " request)
    (if @fork
      ;; Fork is allocated - set a request
      (do
        (swap! request (fn [_] phil-id))
        ;(debug-pr "fork allocated, request: " request)
        )
      ;; Otherwise send the fork
      (do
        (swap! fork (fn [_] phil-id))
        ;(debug-pr "sending fork, request: " request)
        (a/>!! (nth (:from-chans sys) phil-id) fork-id)))))

(defn free-fork [sys fork-id phil-id]
  (let [fork (nth (:forks sys) fork-id)
        request (nth (:fork-requests sys) fork-id)]
    ;(debug-pr "free " fork-id "fork: " fork "request: " request)
    (swap! fork (fn [_] nil))
    (if-let [requester @request]
      (do
        (swap! request (fn [_] nil))
        (swap! fork (fn [_] requester))
        (a/>!! (nth (:from-chans sys) requester) fork-id)))))

(defn request-forks [sys phil-id left right]
  ;(debug-pr phil-id " requests " left " " right)
  (doseq [f (sort [left right])] (allocate-fork sys f phil-id)))

(defn free-forks [sys phil-id left right]
  ;(debug-pr phil-id " frees " left " " right)
  (doseq [f [left right]] (free-fork sys f phil-id) ))

(defn get-next-helping [sys]
  (dosync
    (let [fb (:food-bowl sys)
          food (first @fb)]
      (alter fb rest)
      food)))

(defn send-food [sys phil-id]
  ;; Return a eat time if there is food left, otherwise a sentinel - can't put nil on a channel
  (a/>!! (nth (:from-chans sys) phil-id)
    (if-let [food (get-next-helping sys)] food :no-more)))

(defn display-state [sys phil-id & args]
  (show-line 1 "food left:" (if (get-in sys [:parameters :food-amount]) (count @(:food-bowl sys)) "unlimited"))
  (apply show-line (+ phil-id 3) args)
  )
;(defn echo-sys [a b phil-id sys]
;  (println a b phil-id sys))

(defn dispatch [msg phil-id sys]
  (when (not= msg :end)
    (debug-pr "Waiter" nil "msg received: " msg)
    (let [fn (var-get (find-var (symbol "diningphils.waiter.core" (name (first msg)))))]
      (apply fn sys phil-id (rest msg)))
    true))

(defn run-waiter [sys]
  (try
    (let [to-chans (:to-chans sys)
          chan-indices (:chan-indices sys)]
      (debug-pr "Waiter" nil "started")
      (loop [[v c] (a/alts!! to-chans)]
        (if (dispatch v (get chan-indices c) sys)
          (recur (a/alts!! to-chans)))))
    (catch Throwable e (debug-pr "Waiter" nil "exception: " e "\n" (.printStackTrace e)))))

; Philosoper state fns

(def ^:dynamic *phil-id*)
(def ^:dynamic *phil-name*)
(def ^:dynamic *to-chan*)
(def ^:dynamic *from-chan*)
(def ^:dynamic *left-fork*)
(def ^:dynamic *right-fork*)

(defn send-request [& args]
  (a/>!! *to-chan* args))

(defn ask-waiter [& args]
  (apply send-request args)
  (a/<!! *from-chan*))

(defn show-state [& args]
  (apply send-request 'display-state (str *phil-name* ":") args))

(defn think [ms]
  (show-state "thinking..." )
  (Thread/sleep ms))

(defn eat [ms]
  (show-state "eating with forks" *left-fork* "and" *right-fork* )
  (Thread/sleep ms)
  (send-request 'free-forks *left-fork* *right-fork*))

(defn wait-fork [sys]
  (let [recv-fork (a/<!! *from-chan*)]
    (condp = recv-fork
     *left-fork* (show-state "hungry, has fork" *left-fork* "(left)")
     *right-fork* (show-state "hungry, has fork" *right-fork* "(right)")
     )))

(defn get-forks [sys]
  (show-state "hungry, requests forks" *left-fork* "and" *right-fork*)
  (send-request 'request-forks *left-fork* *right-fork*)
  (wait-fork sys)
  (wait-fork sys))

(defn request-food []
  (let [food (ask-waiter 'send-food)]
    (if (not= food :no-more) food)))

(defn run-phil [sys phil-id left-fork right-fork]
  (binding [*phil-id* phil-id
            *phil-name* (nth (:phil-names sys) phil-id)
            *left-fork* left-fork
            *right-fork*  right-fork                                  ;(mod (inc phil-id) (count (:phil-names sys)))
            *to-chan* (nth (:to-chans sys) phil-id)
            *from-chan* (nth (:from-chans sys) phil-id)]
    (Thread/sleep (random-from-range [5 1]))
    (loop []
      ;; We're now hungry - get forks. If there is food left get and eat it, otherwise we are done
      (get-forks sys)
      (if-let [food-amount (request-food)]
        (do
          (eat food-amount)
          (think (random-from-range (get-in sys [:parameters :think-range])))
          (recur))
        (do
          (send-request 'free-forks *left-fork* *right-fork*)
          (show-state "Done."))))
    ))

