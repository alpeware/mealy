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
      (is (str/includes? result "Aim: To calculate fibonacci numbers"))
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

(deftest compile-prompt-tools-and-bus-topics-test
  (testing "Compilation includes dynamic tool and source awareness"
    (let [state {:aim "To be self-aware"
                 :memory {}
                 :observations []
                 :policies []}
          result (prompt/compile-prompt state)]
      (is (string? result))
      (is (str/includes? result "Available Tools:"))
      (is (str/includes? result ":think"))
      (is (str/includes? result ":eval"))
      (is (str/includes? result "Available Sources (Bus Topics):")))))

(deftest compile-prompt-includes-bootstrap-test
  (testing "Compilation includes the bootstrap source by default"
    (let [state {:aim "Test"
                 :memory {}
                 :observations []
                 :policies []}
          result (prompt/compile-prompt state)]
      (is (str/includes? result "OODA Cognitive Pipeline"))
      (is (str/includes? result "defmethod reducer/handle-event")))))

(defspec ^{:doc "test-compile-prompt-returns-string"} compile-prompt-returns-string 100
  (prop/for-all [state gen-state]
                (let [result (prompt/compile-prompt state)]
                  (and (string? result)
                       (str/includes? result (:aim state))
                       (str/includes? result "Policies:")))))

(deftest sociocratic-system-prompt-includes-policies
  (testing "The system prompt instructs evaluation against Policies alongside Aim and Memory"
    (is (str/includes? prompt/sociocratic-system-prompt "Policies"))))

(deftest compile-tap-system-prompt-test
  (testing "Tap system prompt uses compile-state-context for consistent output"
    (let [state {:aim "Observe"
                 :memory {:current-time "Monday"}
                 :phase :idle
                 :observations [{:a 1}]
                 :policies ["Be polite"]
                 :bus-topics #{:tick}}
          result (prompt/compile-tap-system-prompt state)]
      (is (str/includes? result "A human colleague is checking in"))
      (is (str/includes? result "Current time: Monday"))
      (is (str/includes? result "Aim: Observe"))
      (is (str/includes? result "Phase: idle"))
      (is (str/includes? result "1. Be polite"))
      (is (str/includes? result "EventBus topics: tick"))
      (is (str/includes? result "Available Tools:"))
      (is (str/includes? result ":think"))
      ;; Tap excludes bootstrap to save tokens for conversation
      (is (not (str/includes? result "OODA Cognitive Pipeline")))))
  (testing "Tap system prompt works with empty bus topics"
    (let [state {:aim "Observe"
                 :memory {}
                 :phase :idle
                 :observations []
                 :policies []
                 :bus-topics #{}}
          result (prompt/compile-tap-system-prompt state)]
      (is (str/includes? result "Aim: Observe"))
      ;; No EventBus line when empty
      (is (not (str/includes? result "EventBus topics:"))))))

(deftest compile-state-context-test
  (testing "Unified state context serializes all key fields"
    (let [state {:aim "Test"
                 :memory {:key "value"}
                 :observations [{:a 1} {:b 2}]
                 :policies ["p1"]
                 :phase :idle}
          result (prompt/compile-state-context state)]
      (is (str/includes? result "Aim: Test"))
      (is (str/includes? result "Phase: idle"))
      (is (str/includes? result "1. p1"))
      (is (str/includes? result ":key"))
      (is (str/includes? result "{:a 1}"))
      ;; Includes bootstrap by default
      (is (str/includes? result "OODA Cognitive Pipeline"))))
  (testing "max-observations limits output"
    (let [state {:aim "Test"
                 :memory {}
                 :observations [{:a 1} {:b 2} {:c 3}]
                 :policies []
                 :phase :idle}
          result (prompt/compile-state-context state :max-observations 1)]
      (is (str/includes? result "{:c 3}"))
      (is (not (str/includes? result "{:a 1}")))))
  (testing "include-bootstrap false excludes bootstrap source"
    (let [state {:aim "Test"
                 :memory {}
                 :observations []
                 :policies []
                 :phase :idle}
          result (prompt/compile-state-context state :include-bootstrap false)]
      (is (not (str/includes? result "OODA Cognitive Pipeline")))))
  (testing "bus-topics are included when present"
    (let [state {:aim "Test"
                 :memory {}
                 :observations []
                 :policies []
                 :phase :idle
                 :bus-topics #{:tick :rss}}
          result (prompt/compile-state-context state)]
      (is (str/includes? result "EventBus topics:")))))

(deftest compile-bootstrap-context-test
  (testing "Loads and wraps bootstrap.clj source"
    (let [result (prompt/compile-bootstrap-context)]
      (is (string? result))
      (is (str/includes? result "OODA Cognitive Pipeline"))
      (is (str/includes? result "defmethod reducer/handle-event")))))
