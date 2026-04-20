(ns mealy.intelligence.core-test
  "Tests for mealy.intelligence.core"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.intelligence.core :as core]))

(def gen-valid-config
  "Generator for valid provider configurations."
  (gen/hash-map :provider-id gen/keyword
                :budget gen/s-pos-int
                :rpm gen/s-pos-int))

(defspec ^{:doc "test-initial-state-is-healthy"} initial-state-is-healthy 100
  (prop/for-all [config gen-valid-config]
                (let [state (core/initial-state config)]
                  (and (= (:status state) :healthy)
                       (= (:budget state) (:budget config))
                       (= (:rpm state) (:rpm config))))))

(defspec ^{:doc "test-deduct-budget"} deduct-budget 100
  (prop/for-all [config gen-valid-config
                 tokens gen/s-pos-int]
                (let [state (core/initial-state config)
                      new-state (core/deduct-budget state tokens)]
                  (= (:budget new-state) (- (:budget state) tokens)))))

(deftest provider-backoff-test
  (testing "provider respects backoff state"
    (let [config {:provider-id :test-provider :budget 100 :rpm 60}
          state (core/initial-state config)
          now 100000
          backoff-state (core/set-backoff state 1000 now)]
      (is (= :backoff (:status backoff-state)))
      (is (= (+ now 1000) (:backoff-until backoff-state)))
      (is (true? (core/in-backoff? backoff-state now)))
      (is (false? (core/in-backoff? backoff-state (+ now 2000)))))))

(def gen-provider-state
  "Generates test provider states."
  (gen/hash-map :provider-id gen/keyword
                :budget (gen/choose 0 1000)
                :rpm (gen/choose 10 100)
                :status (gen/elements [:healthy :backoff])
                :complexity (gen/elements [:low :medium :high])))

(defspec ^{:doc "test-score-provider"} score-provider-invariant 100
  (prop/for-all [provider-state gen-provider-state
                 required-complexity (gen/elements [:low :medium :high])
                 estimated-tokens (gen/choose 10 500)]
                (let [score (core/score-provider provider-state required-complexity estimated-tokens)]
                  (if (or (= (:status provider-state) :backoff)
                          (< (:budget provider-state) estimated-tokens))
        ;; A provider in backoff or without enough budget gets a negative score
                    (< score 0)
        ;; A healthy provider with enough budget always gets a positive score
                    (> score 0)))))

(deftest select-best-provider-test
  (testing "selects the best provider"
    (let [provider-states {:p1 {:status :healthy :budget 1000 :complexity :low}
                           :p2 {:status :backoff :budget 1000 :complexity :medium}
                           :p3 {:status :healthy :budget 50 :complexity :high}}]
      ;; Ask for :medium complexity, but p2 is in backoff.
      ;; p3 doesn't have enough budget (requires 100, has 50).
      ;; p1 is healthy and has budget, so it should be selected despite being :low.
      (is (= :p1 (core/select-best-provider provider-states :medium 100))))))

(deftest select-best-provider-fallback-ordering-test
  (testing "when :high is in backoff, :medium is chosen over :low"
    (let [provider-states {:p0 {:status :backoff :budget 1000000000 :complexity :high
                                :backoff-until (+ (System/currentTimeMillis) 60000)}
                           :p1 {:status :healthy :budget 100000000 :complexity :medium}
                           :p2 {:status :healthy :budget 99996069 :complexity :low}}]
      (is (= :p1 (core/select-best-provider provider-states :high 1000)))))

  (testing "when :high and :medium are in backoff, :low is chosen"
    (let [provider-states {:p0 {:status :backoff :budget 1000000 :complexity :high}
                           :p1 {:status :backoff :budget 1000000 :complexity :medium}
                           :p2 {:status :healthy :budget 1000000 :complexity :low}}]
      (is (= :p2 (core/select-best-provider provider-states :high 1000)))))

  (testing ":medium is preferred over :low when requesting :high"
    (let [scores-medium (core/score-provider {:status :healthy :budget 1000 :complexity :medium} :high 100)
          scores-low (core/score-provider {:status :healthy :budget 1000 :complexity :low} :high 100)]
      (is (> scores-medium scores-low)
          ":medium should score higher than :low as fallback for :high")))

  (testing "exact match always beats fallback"
    (let [scores-exact (core/score-provider {:status :healthy :budget 1000 :complexity :high} :high 100)
          scores-over (core/score-provider {:status :healthy :budget 1000 :complexity :medium} :high 100)]
      (is (> scores-exact scores-over)))))
