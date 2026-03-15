(ns mealy.cell.mitosis-test
  "Tests for mealy.cell.mitosis"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.core :as cell]
            [mealy.cell.mitosis :as mitosis]))

(defspec ^{:doc "Generative invariant: divide cleanly partitions memory without overlap or loss."}
  test-divide-memory-partition 100
  (prop/for-all [aim (gen/return "Aim")
                 child-aim (gen/return "Child Aim")
                 memory (gen/map gen/keyword gen/any)
                 partition-keys (gen/fmap set (gen/vector gen/keyword))]
                (let [parent (cell/make-cell aim memory)
          ;; Only partition keys that actually exist in memory
                      child-keys (set/intersection (set (keys memory)) partition-keys)
                      result (mitosis/divide parent child-keys child-aim)
                      new-parent (:parent result)
                      child (:child result)
                      parent-mem-keys (set (keys (:memory new-parent)))
                      child-mem-keys (set (keys (:memory child)))]
                  (and (map? result)
                       (= aim (:aim new-parent))
                       (= child-aim (:aim child))
                       (empty? (set/intersection parent-mem-keys child-mem-keys))
                       (= (set (keys memory)) (set/union parent-mem-keys child-mem-keys))
                       (= (:memory child) (select-keys memory child-keys))
                       (= (:memory new-parent) (apply dissoc memory child-keys))))))

(deftest test-threshold-reached
  (testing "threshold-reached? evaluates complexity based on memory and observation counts"
    (let [state (cell/make-cell "Aim" {:a 1 :b 2 :c 3})]
      (is (true? (mitosis/threshold-reached? state {:max-memory-keys 2})))
      (is (false? (mitosis/threshold-reached? state {:max-memory-keys 5})))

      (let [state-with-obs (assoc state :observations [1 2 3])]
        (is (true? (mitosis/threshold-reached? state-with-obs {:max-observations 2})))
        (is (false? (mitosis/threshold-reached? state-with-obs {:max-observations 5})))))))
