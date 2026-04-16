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
                       (vector? (:policies c))
                       (empty? (:policies c))
                       (= #{} (:bus-topics c))
                       (= :anchor (:parent c))
                       (some? (:sci-ctx c))))))

(deftest test-cell-creation
  (testing "Cell initialization"
    (let [c (cell/make-cell "Survive" {:health 100})]
      (is (= "Survive" (:aim c)))
      (is (= {:health 100} (:memory c)))
      (is (= [] (:observations c)))
      (is (= [] (:policies c)))
      (is (= #{} (:bus-topics c)))
      (is (= :anchor (:parent c)))
      (is (some? (:sci-ctx c))))))

(deftest test-sanitize-for-snapshot
  (testing "sanitize-for-snapshot strips transient keys"
    (let [c (cell/make-cell "Survive" {:health 100})
          sanitized (cell/sanitize-for-snapshot c)]
      (is (nil? (:sci-ctx sanitized)))
      (is (= "Survive" (:aim sanitized)))
      (is (= {:health 100} (:memory sanitized))))))

(deftest test-make-sci-ctx-fork
  (testing "Forking a SCI context creates an independent copy"
    (let [parent-ctx (cell/make-sci-ctx)
          child-ctx (cell/make-sci-ctx parent-ctx)]
      (is (some? parent-ctx))
      (is (some? child-ctx))
      (is (not= parent-ctx child-ctx)))))
