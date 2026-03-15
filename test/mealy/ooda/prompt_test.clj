(ns mealy.ooda.prompt-test
  "Tests for mealy.ooda.prompt"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.ooda.prompt :as prompt]))

;; Generators for the state map elements
(def gen-aim
  "Generator for cell aim"
  gen/string-alphanumeric)

(def gen-memory
  "Generator for cell memory"
  (gen/map gen/keyword gen/string-alphanumeric))

(def gen-observations
  "Generator for cell observations"
  (gen/vector (gen/tuple gen/keyword gen/any)))

(def gen-state
  "Generator for a valid cell state map"
  (gen/hash-map :aim gen-aim
                :memory gen-memory
                :observations gen-observations))

(deftest compile-prompt-basic-test
  (testing "Basic compilation of a prompt from state"
    (let [state {:aim "To calculate fibonacci numbers"
                 :memory {:history "calculated 1, 1, 2"}
                 :observations [[:calculation 3] [:calculation 5]]}
          result (prompt/compile-prompt state)]
      (is (string? result))
      (is (str/includes? result "Aim:\nTo calculate fibonacci numbers"))
      (is (str/includes? result "Memory:"))
      (is (str/includes? result "history"))
      (is (str/includes? result "calculated 1, 1, 2"))
      (is (str/includes? result "Observations:"))
      (is (str/includes? result ":calculation 3"))
      (is (str/includes? result "Consent-based LLM evaluation")))))

(defspec ^{:doc "test-compile-prompt-returns-string"} compile-prompt-returns-string 100
  (prop/for-all [state gen-state]
                (let [result (prompt/compile-prompt state)]
                  (and (string? result)
                       (str/includes? result (:aim state))))))
