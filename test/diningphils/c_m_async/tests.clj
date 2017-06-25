(ns diningphils.c-m-async.tests
  (:use diningphils.c-m-agents.core
        diningphils.c-m-async.system
        clojure.test))

#_(deftest id-wrap
  (doseq [id (range 5)] (is (= id (wrapped-phil-id id))))
  (is (= 0 (wrapped-phil-id 5)))
  (is (= 4 (wrapped-phil-id -1))))

(deftest channel-init
  (let [phils (:phils (init-fn {}))]
    (testing "Values"
      (doseq [phil phils]
        (is (:phil-id phil))
        (is (identical? phil (nth phils (:phil-id phil))))))

    (testing "Channel connections"
      (doseq [phil phils]
        (let [phil-id (:phil-id phil)
              n (count phils)
              neighbors (:neighbors phil)
              left (nth phils (mod (dec phil-id) n))
              right (nth phils (mod (inc phil-id) n))]
          ;; Left neighbors chans should be my chans
          (is (identical? (:to (last (:neighbors left))) (:from (:chans phil))))
          (is (identical? (:from (last (:neighbors left))) (:to (:chans phil))))
          (is (identical? (:to (last (:neighbors left))) (:from (first neighbors))))
          (is (identical? (:from (last (:neighbors left))) (:to (first neighbors))))
          (is (identical? (:to (first (:neighbors right))) (:from (last neighbors))))
          (is (identical? (:from (first (:neighbors right))) (:to (last neighbors))))
          )))
    ))


