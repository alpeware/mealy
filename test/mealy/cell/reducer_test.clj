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

(def gen-consent-response
  "Generator for strings that should parse as consent."
  (gen/fmap (fn [s] (str "CONSENT: " s)) gen/string-alphanumeric))

(def gen-objection-response
  "Generator for strings that should parse as an objection."
  (gen/fmap (fn [s] (str "OBJECTION: " s)) gen/string-alphanumeric))

(defspec ^{:doc "test-parse-consent-identifies-consent"} parse-consent-identifies-consent 100
  (prop/for-all [response gen-consent-response]
                (let [result (reducer/parse-consent response)]
                  (and (true? (:consent result))
                       (= response (:response result))))))

(defspec ^{:doc "test-parse-consent-identifies-objection"} parse-consent-identifies-objection 100
  (prop/for-all [response gen-objection-response]
                (let [result (reducer/parse-consent response)]
                  (and (false? (:consent result))
                       (= response (:response result))))))
