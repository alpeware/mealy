(ns mealy.intelligence.gateway-test
  "Tests for mealy.intelligence.gateway"
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [clojure.test :refer [deftest is testing]]
            [mealy.intelligence.gateway :as gateway]))

(deftest start-gateway-test
  (testing "gateway processes llm-requests and emits observations using router-chan"
    (let [cmd-chan (async/chan 10)
          cell-in-chan (async/chan 10)
          router-chan (async/chan 10)]

      ;; Start the gateway
      (gateway/start-gateway cmd-chan cell-in-chan router-chan)

      ;; Send a command to the gateway
      (>!! cmd-chan {:type :llm-request :prompt "test-prompt" :callback-event :my-event :estimated-tokens 50 :complexity :low})

      ;; Mock the router behavior
      (let [router-cmd (<!! router-chan)]
        (is (= :evaluate (:type router-cmd)))
        (is (= "test-prompt" (:prompt router-cmd)))
        (is (= 50 (:estimated-tokens router-cmd)))
        (is (= :low (:complexity router-cmd)))
        ;; Send back a successful response on the reply-chan
        (>!! (:reply-chan router-cmd) {:response "some raw llm response"}))

      ;; Read the resulting observation from the cell's input channel
      (let [observation (<!! cell-in-chan)]
        (is (= :observation (first observation)))
        (let [obs-data (second observation)]
          (is (= :my-event (:type obs-data)))
          (is (= "some raw llm response" (:response obs-data)))))

      ;; Clean up
      (async/close! cmd-chan)
      (async/close! router-chan)))

  (testing "gateway handles router errors gracefully"
    (let [cmd-chan (async/chan 10)
          cell-in-chan (async/chan 10)
          router-chan (async/chan 10)]

      ;; Start the gateway
      (gateway/start-gateway cmd-chan cell-in-chan router-chan)

      ;; Send a command to the gateway
      (>!! cmd-chan {:type :llm-request :prompt "test-prompt" :callback-event :my-event})

      ;; Mock the router behavior sending an error
      (let [router-cmd (<!! router-chan)]
        (is (= :evaluate (:type router-cmd)))
        (>!! (:reply-chan router-cmd) {:status :error :reason :no-available-provider}))

      ;; Read the resulting error observation from the cell's input channel
      (let [observation (<!! cell-in-chan)]
        (is (= :observation (first observation)))
        (let [obs-data (second observation)]
          (is (= :evaluation-error (:type obs-data)))
          (is (= :no-available-provider (:reason obs-data)))))

      ;; Clean up
      (async/close! cmd-chan)
      (async/close! router-chan))))