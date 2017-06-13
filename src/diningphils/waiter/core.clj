(ns diningphils.waiter.core
  (:require [clojure.core.async :as a])
  (:use [diningphils.utils])
  (:import (java.util.concurrent CancellationException)))

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

(defn send-food [sys phil-id]
  ;; Return true if there is food left (> 0 @food-bowl) or there is unlimited food (nil? @food-bowl).
  ;; If there is food, remove a helping
  (a/>!! (nth (:from-chans sys) phil-id)
    (let [food @(:food-bowl sys)
         food-left (and food (> food 0))]
     (when food-left (swap! (:food-bowl sys) dec))
     (or (nil? food) food-left))))

(defn is-food-left? [sys phil-id]
  (a/>!! (nth (:from-chans sys) phil-id) (or (nil? @(:food-bowl sys)) (> @(:food-bowl sys) 0))))

(defn display-state [sys phil-id & args]
  (show-line 1 "food left: " (if-let [f @(:food-bowl sys)] f "unlimited"))
  (apply show-line (+ phil-id 3) args)
  (flush)
  )
;(defn echo-sys [a b phil-id sys]
;  (println a b phil-id sys))

(defn dispatch [msg phil-id sys]
  (when (not= msg :end)
    (let [fn (var-get (find-var (symbol "diningphils.waiter.core" (name (first msg)))))]
      ;(println "dispatch " msg "to" phil-id)
      (apply fn sys phil-id (rest msg)))
    true))

(defn run-waiter [sys]
  (let [to-chans (:to-chans sys)
        chan-indices (:chan-indices sys)]
    (loop [[v c] (a/alts!! to-chans)]
      (if (dispatch v (get chan-indices c) sys)
       (recur (a/alts!! to-chans))))))

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
  (a/>!! *to-chan* args)
  (a/<!! *from-chan*))

(defn show-state [& args]
  (apply send-request 'display-state (str *phil-name* ": ") args)
  )

(defn think [ms]
  (show-state "thinking..." )
  (Thread/sleep ms))

(defn eat [ms]
  (show-state "eating with forks " *left-fork* " and " *right-fork* )
  (Thread/sleep ms)
  (send-request 'free-forks *left-fork* *right-fork*))

(defn wait-fork [sys]
  (let [recv-fork (a/<!! *from-chan*)]
    (condp = recv-fork
     *left-fork* (show-state "hungry, has fork " *left-fork* " (left)")
     *right-fork* (show-state "hungry, has fork " *right-fork* " (right)")
     )))

(defn get-forks [sys]
  (show-state "hungry, requests forks " *left-fork* " and " *right-fork*)
  (send-request 'request-forks *left-fork* *right-fork*)
  (wait-fork sys)
  (wait-fork sys))

(defn run-phil [phil-id sys]
  (binding [*phil-id* phil-id
            *phil-name* (nth (:phil-names sys) phil-id)
            *left-fork* phil-id
            *right-fork* (mod (inc phil-id) (count (:phil-names sys)))
            *to-chan* (nth (:to-chans sys) phil-id)
            *from-chan* (nth (:from-chans sys) phil-id)]
    (Thread/sleep (random-from-range [5 1]))
    (loop []
      ;; We're now hungry - if there is food left, get forks and eat
      (if (ask-waiter 'send-food)
        (do
          (get-forks sys)
          (eat (random-from-range (get-in sys [:parameters :eat-range])))
          (think (random-from-range (get-in sys [:parameters :think-range])))
          (recur))
        (show-state "Done.")))))

