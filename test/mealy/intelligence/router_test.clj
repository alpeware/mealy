(ns mealy.intelligence.router-test
  "Tests for mealy.intelligence.router"
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.intelligence.router :as router]))

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
                (let [score (router/score-provider provider-state required-complexity estimated-tokens)]
                  (if (or (= (:status provider-state) :backoff)
                          (< (:budget provider-state) estimated-tokens))
        ;; A provider in backoff or without enough budget gets a negative score
                    (< score 0)
        ;; A healthy provider with enough budget gets a positive score
        ;; and score is higher if complexities match
                    (>= score 0)))))

(deftest start-router-test
  (testing "router multiplexes and selects the best provider"
    (let [cmd-chan (async/chan 10)
          ;; Mock providers
          p1-chan (async/chan 10)
          p2-chan (async/chan 10)
          p3-chan (async/chan 10)
          registry {:p1 {:chan p1-chan :complexity :low}
                    :p2 {:chan p2-chan :complexity :medium}
                    :p3 {:chan p3-chan :complexity :high}}
          _router-chan (router/start-router registry cmd-chan)]

      ;; Background loop to mock provider responses to :status and :evaluate commands
      (async/go-loop []
        (let [[val port] (async/alts! [p1-chan p2-chan p3-chan])]
          (when val
            (let [{:keys [type reply-chan]} val]
              (case type
                :status
                (cond
                  (= port p1-chan) (async/>! reply-chan {:provider-id :p1 :status :healthy :budget 1000})
                  (= port p2-chan) (async/>! reply-chan {:provider-id :p2 :status :backoff :budget 1000})
                  (= port p3-chan) (async/>! reply-chan {:provider-id :p3 :status :healthy :budget 50}))
                :evaluate
                (cond
                  (= port p1-chan) (async/>! reply-chan {:tokens 10 :response "p1"})
                  (= port p2-chan) (async/>! reply-chan {:tokens 10 :response "p2"})
                  (= port p3-chan) (async/>! reply-chan {:tokens 10 :response "p3"})))
              (recur)))))

      ;; Test 1: Ask for :medium complexity, but p2 is in backoff.
      ;; p3 doesn't have enough budget (requires 100, has 50).
      ;; p1 is healthy and has budget, so it should be selected despite being :low.
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :evaluate :prompt "test" :estimated-tokens 100 :complexity :medium :reply-chan reply-chan})
        (let [result (<!! reply-chan)]
          (is (= "p1" (:response result)))
          (is (= 10 (:tokens result)))))

      ;; Clean up
      (async/close! cmd-chan)
      (async/close! p1-chan)
      (async/close! p2-chan)
      (async/close! p3-chan))))
