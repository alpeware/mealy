(ns mealy.cell.reducer-test
  "Tests for mealy.cell.reducer"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.core :as cell]
            [mealy.cell.reducer :as reducer]))

(defspec ^{:doc "Generative invariant: handle-event always returns a valid state and commands vector."}
  test-handle-event-invariant 100
  (prop/for-all [aim (gen/one-of [(gen/return "a string") (gen/return :a-keyword) (gen/return 'a-symbol)])
                 memory (gen/map gen/keyword gen/any)
                 event (gen/tuple gen/keyword gen/any)]
                (let [c (cell/make-cell aim memory)
                      result (reducer/handle-event c event)]
                  (and (map? result)
                       (contains? result :state)
                       (contains? result :commands)
                       (map? (:state result))
                       (vector? (:commands result))))))

(deftest test-handle-observation
  (testing "[:observation data] event appends data to state's observations and returns empty commands"
    (let [c (cell/make-cell "Survive" {})
          event [:observation {:temp 98.6}]
          result (reducer/handle-event c event)]
      (is (= [{:temp 98.6}] (get-in result [:state :observations])))
      (is (= [] (:commands result))))))

(deftest test-handle-unknown-event
  (testing "Unknown event returns state unchanged and empty commands"
    (let [c (cell/make-cell "Survive" {})
          event [:unknown-event {:foo :bar}]
          result (reducer/handle-event c event)]
      (is (= c (:state result)))
      (is (= [] (:commands result))))))
