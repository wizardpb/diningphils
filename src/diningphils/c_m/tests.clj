(ns diningphils.c-m.tests
  (:use diningphils.c-m.core clojure.test))

(deftest id-wrap
  (doseq [id (range 5)] (is (= id (wrapped-phil-id id))))
  (is (= 0 (wrapped-phil-id 5)))
  (is (= 4 (wrapped-phil-id -1))))

(deftest channel-init
  (is (count channels) phil-count)
  (doseq
    [phil-id (range 5)]
    (let [left-rec (nth channels (wrapped-phil-id (dec phil-id)))
          right-rec (nth channels (wrapped-phil-id (inc phil-id)))
          this-rec (nth channels phil-id)]
      (is (every? #(not (nil? %)) [left-rec this-rec right-rec]))
      (is (identical? (:to-left this-rec) (:from-right left-rec)))
      (is (identical? (:to-right this-rec) (:from-left right-rec)))
      (is (identical? (:from-left this-rec) (:to-right left-rec)))
      (is (identical? (:from-right this-rec) (:to-left right-rec))))))

(deftest phil-init
  (doseq
    [phil-id (range 5)]
    (let [this-rec (nth channels phil-id)
          recv-chans [(:from-left this-rec) (:from-right this-rec)]
          reqst-chans [(:to-left this-rec) (:to-right this-rec)]]
      (is (.equals (receive-chans-for phil-id) recv-chans))
      (is (.equals (request-chans-for phil-id) reqst-chans)))))

(deftest fork-init
  (is (= (count forks) phil-count))
  (doseq
    [fork-id (range phil-count)]
      (is (.equals @(nth-fork fork-id) {:owner (if (zero? fork-id) max-phil-id fork-id) :dirty? true}))))

(deftest state-access
  (with-bindings-for 4
    (fn []
      (is (and (= *left-fork* 4) (= *right-fork* 0)))
      (is (= (local-fork-index 4) 0))
      (is (= (local-fork-index 0) 1))
      (is (not (nil? (internal-state))))
      (is (has-fork? 4))
      (is (has-fork? 0))
      (is (dirty? 4))
      (is (dirty? 0))
      )))

(run-tests)