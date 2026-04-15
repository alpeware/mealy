(ns mealy.subscription.core-test
  "Tests for mealy.subscription.core"
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [mealy.subscription.core :as sub]))

(deftest test-tick-subscription-start-stop
  (testing "Starting a :tick subscription delivers tick events to the cell-in-chan"
    (let [in-chan (async/chan 100)
          handle (sub/start-subscription {:type :tick :interval-ms 50}
                                         {:cell-in-chan in-chan})]
      (try
        ;; Wait for a couple of ticks
        (let [[val _] (async/alts!! [in-chan (async/timeout 500)])]
          (is (some? val) "Should receive at least one tick event")
          (is (= :tick (first val)) "Event should be a :tick")
          (is (number? (:timestamp (second val))) "Should contain a numeric timestamp"))

        ;; Check a second tick arrives
        (let [[val _] (async/alts!! [in-chan (async/timeout 500)])]
          (is (some? val) "Should receive a second tick"))

        (finally
          ;; Stop the subscription
          (sub/stop-subscription {:type :tick} handle)
          ;; Give it a moment to stop
          (async/<!! (async/timeout 100))
          ;; Drain any remaining events
          (loop []
            (let [[val _] (async/alts!! [in-chan (async/timeout 200)])]
              (when val (recur))))
          (async/close! in-chan))))))

(deftest test-tick-subscription-stops-cleanly
  (testing "After stopping a :tick subscription, no more events are delivered"
    (let [in-chan (async/chan 100)
          handle (sub/start-subscription {:type :tick :interval-ms 50}
                                         {:cell-in-chan in-chan})]
      ;; Wait for one tick to confirm it's alive
      (let [[val _] (async/alts!! [in-chan (async/timeout 500)])]
        (is (some? val)))

      ;; Stop
      (sub/stop-subscription {:type :tick} handle)
      (async/<!! (async/timeout 150))

      ;; Drain any buffered ticks
      (loop []
        (let [[val _] (async/alts!! [in-chan (async/timeout 50)])]
          (when val (recur))))

      ;; No more ticks should arrive after drain
      (let [[val _] (async/alts!! [in-chan (async/timeout 200)])]
        (is (nil? val) "No events should arrive after stopping"))

      (async/close! in-chan))))
