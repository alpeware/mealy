(ns mealy.intelligence.provider-test
  "Tests for mealy.intelligence.provider"
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.intelligence.provider :as provider]))

(def gen-valid-config
  "Generator for valid provider configurations."
  (gen/hash-map :provider-id gen/keyword
                :budget gen/s-pos-int
                :rpm gen/s-pos-int))

(defspec ^{:doc "test-initial-state-is-healthy"} initial-state-is-healthy 100
  (prop/for-all [config gen-valid-config]
                (let [state (provider/initial-state config)]
                  (and (= (:status state) :healthy)
                       (= (:budget state) (:budget config))
                       (= (:rpm state) (:rpm config))))))

(defspec ^{:doc "test-deduct-budget"} deduct-budget 100
  (prop/for-all [config gen-valid-config
                 tokens gen/s-pos-int]
                (let [state (provider/initial-state config)
                      new-state (provider/deduct-budget state tokens)]
                  (= (:budget new-state) (- (:budget state) tokens)))))

(deftest start-provider-test
  (testing "provider actor maintains state and processes commands"
    (let [cmd-chan (async/chan 10)
          llm-fn (fn [messages]
                   (let [c (async/chan)]
                     (async/go
                       (async/<! (async/timeout 10))
                       (async/>! c {:tokens 10 :response (str "Resp: " (:content (first messages)))}))
                     c))
          config {:provider-id :test-provider :budget 100 :rpm 60}
          _provider-chan (provider/start-provider config cmd-chan llm-fn)]

      ;; Request initial status
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :status :reply-chan reply-chan})
        (let [status (<!! reply-chan)]
          (is (= :healthy (:status status)))
          (is (= 100 (:budget status)))))

      ;; Send an evaluate command
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :evaluate :messages [{:role "user" :content "hello"}] :estimated-tokens 10 :reply-chan reply-chan})
        (let [result (<!! reply-chan)]
          (is (= "Resp: hello" (:response result)))
          (is (= 10 (:tokens result)))))

      ;; Request status again to verify budget deduction
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :status :reply-chan reply-chan})
        (let [status (<!! reply-chan)]
          (is (= :healthy (:status status)))
          (is (= 90 (:budget status)))))

      ;; Clean up
      (async/close! cmd-chan))))

(deftest provider-backoff-test
  (testing "provider actor respects backoff state"
    (let [config {:provider-id :test-provider :budget 100 :rpm 60}
          state (provider/initial-state config)
          backoff-state (provider/set-backoff state 1000)
          now (System/currentTimeMillis)]
      (is (= :backoff (:status backoff-state)))
      (is (some? (:backoff-until backoff-state)))
      (is (true? (provider/in-backoff? backoff-state now)))
      (is (false? (provider/in-backoff? backoff-state (+ now 2000))))))

  (testing "provider loop transitions into and out of backoff state"
    (let [cmd-chan (async/chan 10)
          llm-fn (fn [_]
                   (let [c (async/chan)]
                     (async/go
                       (async/>! c {:error true :backoff-ms 500 :reason "Rate limit exceeded"}))
                     c))
          config {:provider-id :test-provider :budget 100 :rpm 60}
          _provider-chan (provider/start-provider config cmd-chan llm-fn)]

      ;; Send an evaluate command to trigger the error and backoff
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :evaluate :messages [{:role "user" :content "hello"}] :estimated-tokens 10 :reply-chan reply-chan})
        ;; Wait for the error response
        (let [result (<!! reply-chan)]
          (is (true? (:error result)))
          (is (= "Rate limit exceeded" (:reason result)))))

      ;; Request status to verify we are now in backoff
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :status :reply-chan reply-chan})
        (let [status (<!! reply-chan)]
          (is (= :backoff (:status status)))
          (is (some? (:backoff-until status)))))

      ;; Try to evaluate again, it should be rejected due to backoff
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :evaluate :messages [{:role "user" :content "hello again"}] :estimated-tokens 10 :reply-chan reply-chan})
        (let [result (<!! reply-chan)]
          (is (= :error (:status result)))
          (is (= :in-backoff (:reason result)))))

      ;; Wait for backoff to expire
      (<!! (async/timeout 600))

      ;; Request status again to verify backoff expired (it expires on next message processing)
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :status :reply-chan reply-chan})
        (let [status (<!! reply-chan)]
          (is (= :healthy (:status status)))
          (is (nil? (:backoff-until status)))))

      (async/close! cmd-chan))))

(deftest provider-budget-exhausted-test
  (testing "provider actor rejects evaluation when budget is exhausted"
    (let [cmd-chan (async/chan 10)
          llm-fn (fn [_] (async/chan))
          config {:provider-id :test-provider :budget 5 :rpm 60}
          _provider-chan (provider/start-provider config cmd-chan llm-fn)]

      ;; Send an evaluate command that requires more tokens than available
      (let [reply-chan (async/chan)]
        (>!! cmd-chan {:type :evaluate :messages [{:role "user" :content "hello"}] :estimated-tokens 10 :reply-chan reply-chan})
        (let [result (<!! reply-chan)]
          (is (= :error (:status result)))
          (is (= :insufficient-budget (:reason result)))))

      (async/close! cmd-chan))))
