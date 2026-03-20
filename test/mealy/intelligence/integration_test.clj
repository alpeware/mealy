(ns mealy.intelligence.integration-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]
            [mealy.intelligence.provider :as provider]
            [mealy.intelligence.router :as router]))

(deftest action-to-provider-flow-test
  (testing "End-to-end flow from llm-request action to provider and back to cell"
    (let [cell-in-chan (async/chan 10)
          router-cmd-chan (async/chan 10)
          provider-low-chan (async/chan 10)
          provider-high-chan (async/chan 10)

          dummy-llm-fn (fn [_messages]
                         (let [out (async/chan 1)]
                           (async/go
                             (async/<! (async/timeout 10))
                             (async/>! out {:tokens 15 :response "Mock LLM Response"})
                             (async/close! out))
                           out))]
      (try
        ;; Spin up providers
        (provider/start-provider
         {:provider-id :provider-low :budget 1000 :rpm 60}
         provider-low-chan
         dummy-llm-fn)
        (provider/start-provider
         {:provider-id :provider-high :budget 1000 :rpm 60}
         provider-high-chan
         dummy-llm-fn)

        ;; Spin up router
        (router/start-router
         {:provider-low {:chan provider-low-chan :complexity :low}
          :provider-high {:chan provider-high-chan :complexity :high}}
         router-cmd-chan)

        ;; Execute the llm-request action directly
        (action/execute
         {:type :llm-request
          :messages [{:role "user" :content "Hello"}]
          :complexity :low
          :callback-event :hello-received}
         {:router-chan router-cmd-chan
          :cell-in-chan cell-in-chan})

        ;; Wait for the result
        (let [[val port] (async/alts!! [cell-in-chan (async/timeout 1000)])]
          (is (= port cell-in-chan) "Did not receive response within 1000ms")
          (is (= [:hello-received {:response "Mock LLM Response"}] val)))

        (finally
          (async/close! cell-in-chan)
          (async/close! router-cmd-chan)
          (async/close! provider-low-chan)
          (async/close! provider-high-chan))))))
