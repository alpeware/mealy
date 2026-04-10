(ns mealy.cell.core-test
  "Tests for mealy.cell.core"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.core :as cell]))

(defspec ^{:doc "Verifies the invariants of the basic cell data structure"} test-cell-invariants 100
  (prop/for-all [aim (gen/one-of [(gen/return "a string") (gen/return :a-keyword) (gen/return 'a-symbol)])
                 memory (gen/map gen/keyword gen/any)]
                (let [c (cell/make-cell aim memory)]
                  (and (= (:aim c) aim)
                       (= (:memory c) memory)
                       (vector? (:observations c))
                       (empty? (:observations c))
                       (= #{} (:subscriptions c))
                       (= {} (:handlers c))
                       (= {} (:actions c))
                       (nil? (:parent c))
                       (= #{} (:children c))))))

(deftest test-cell-creation
  (testing "Cell initialization"
    (let [c (cell/make-cell "Survive" {:health 100})]
      (is (= "Survive" (:aim c)))
      (is (= {:health 100} (:memory c)))
      (is (= [] (:observations c)))
      (is (= #{} (:subscriptions c)))
      (is (= {} (:handlers c)))
      (is (= {} (:actions c)))
      (is (nil? (:parent c)))
      (is (= #{} (:children c))))))
