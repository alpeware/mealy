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

(def gen-policies
  "Generator for cell policies"
  (gen/vector gen/string-alphanumeric))

(def gen-state
  "Generator for a valid cell state map"
  (gen/hash-map :aim gen-aim
                :memory gen-memory
                :observations gen-observations
                :policies gen-policies))

(deftest compile-prompt-basic-test
  (testing "Basic compilation of a prompt from state"
    (let [state {:aim "To calculate fibonacci numbers"
                 :memory {:history "calculated 1, 1, 2"}
                 :observations [[:calculation 3] [:calculation 5]]
                 :policies ["Only use pure functions"]}
          result (prompt/compile-prompt state)]
      (is (string? result))
      (is (str/includes? result "Aim:\nTo calculate fibonacci numbers"))
      (is (str/includes? result "Memory:"))
      (is (str/includes? result "history"))
      (is (str/includes? result "calculated 1, 1, 2"))
      (is (str/includes? result "Observations:"))
      (is (str/includes? result ":calculation 3"))
      (is (str/includes? result "Consent-based LLM evaluation"))
      (is (str/includes? result "Policies:"))
      (is (str/includes? result "1. Only use pure functions")))))

(deftest compile-prompt-no-policies-test
  (testing "Compilation with no policies shows (none)"
    (let [state {:aim "Test"
                 :memory {}
                 :observations []
                 :policies []}
          result (prompt/compile-prompt state)]
      (is (str/includes? result "Policies:\n(none)")))))

(deftest compile-prompt-tools-and-subscriptions-test
  (testing "Compilation includes dynamic tool and subscription awareness"
    (let [state {:aim "To be self-aware"
                 :memory {}
                 :observations []
                 :policies []}
          result (prompt/compile-prompt state)]
      (is (string? result))
      (is (str/includes? result "Available Tools:"))
      (is (str/includes? result ":think"))
      (is (str/includes? result ":eval"))
      (is (str/includes? result "Available Subscriptions:")))))

(defspec ^{:doc "test-compile-prompt-returns-string"} compile-prompt-returns-string 100
  (prop/for-all [state gen-state]
                (let [result (prompt/compile-prompt state)]
                  (and (string? result)
                       (str/includes? result (:aim state))
                       (str/includes? result "Policies:")))))

(deftest sociocratic-system-prompt-includes-policies
  (testing "The system prompt instructs evaluation against Policies alongside Aim and Memory"
    (is (str/includes? prompt/sociocratic-system-prompt "Policies"))))

(deftest compile-tap-system-prompt-subscriptions-test
  (testing "Tap system prompt includes current subscriptions from state"
    (let [state {:aim "Observe"
                 :memory {:current-time "Monday"}
                 :phase :idle
                 :observations []
                 :policies []
                 :subscriptions #{:tick}}
          result (prompt/compile-tap-system-prompt state)]
      (is (str/includes? result "Current subscriptions: tick"))))
  (testing "Tap system prompt shows (none) when no subscriptions"
    (let [state {:aim "Observe"
                 :memory {}
                 :phase :idle
                 :observations []
                 :policies []
                 :subscriptions #{}}
          result (prompt/compile-tap-system-prompt state)]
      (is (str/includes? result "Current subscriptions: (none)")))))
