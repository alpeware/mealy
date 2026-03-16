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
                 phase (gen/elements [:idle :evaluating :acting])
                 event (gen/tuple gen/keyword gen/any)]
                (let [c (assoc (cell/make-cell aim memory) :phase phase)
                      result (reducer/handle-event c event)]
                  (and (map? result)
                       (contains? result :state)
                       (contains? result :commands)
                       (map? (:state result))
                       (vector? (:commands result))
                       (contains? (:state result) :phase)))))

(deftest test-handle-observation-idle
  (testing "[:observation data] event while :idle appends data to state's observations, transitions to :evaluating, and yields a :llm-request command"
    (let [c (cell/make-cell "Survive" {})
          event [:observation {:temp 98.6}]
          result (reducer/handle-event c event)
          new-state (:state result)
          commands (:commands result)]
      (is (= [{:temp 98.6}] (:observations new-state)))
      (is (= :evaluating (:phase new-state)))
      (is (= 1 (count commands)))
      (is (= :llm-request (:type (first commands))))
      (is (= :high (:complexity (first commands))))
      (is (= :consent-evaluated (:callback-event (first commands)))))))

(deftest test-handle-observation-not-idle
  (testing "[:observation data] event while not :idle buffers observation but yields no commands"
    (let [c (assoc (cell/make-cell "Survive" {}) :phase :evaluating)
          event [:observation {:temp 98.6}]
          result (reducer/handle-event c event)
          new-state (:state result)
          commands (:commands result)]
      (is (= [{:temp 98.6}] (:observations new-state)))
      (is (= :evaluating (:phase new-state)))
      (is (empty? commands)))))

(deftest test-handle-consent-evaluated-positive
  (testing "[:consent-evaluated data] with positive consent transitions to :acting and yields an :execute-action command"
    (let [c (assoc (cell/make-cell "Survive" {}) :phase :evaluating)
          event [:consent-evaluated {:response "I CONSENT to this action"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          commands (:commands result)]
      (is (= :acting (:phase new-state)))
      (is (= 1 (count commands)))
      (is (= :execute-action (:type (first commands)))))))

(deftest test-handle-consent-evaluated-objection
  (testing "[:consent-evaluated data] with objection transitions to :idle and yields no commands"
    (let [c (assoc (cell/make-cell "Survive" {}) :phase :evaluating)
          event [:consent-evaluated {:response "I have an OBJECTION to this action"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          commands (:commands result)]
      (is (= :idle (:phase new-state)))
      (is (empty? commands)))))

(deftest test-handle-evaluation-error
  (testing "[:evaluation-error data] transitions to :idle, records the error, and yields no commands"
    (let [c (assoc (cell/make-cell "Survive" {}) :phase :evaluating)
          event [:evaluation-error {:reason "Timeout"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          commands (:commands result)]
      (is (= :idle (:phase new-state)))
      (is (= "Timeout" (:last-error new-state)))
      (is (empty? commands)))))

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
