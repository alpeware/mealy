(ns mealy.action.core-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]))

(defmethod action/execute :mock-action
  [_ env]
  (:mock-result env))

(deftest test-execute-multimethod
  (testing "execute dispatches based on the :type of the action"
    (let [action {:type :mock-action}
          env {:mock-result :success}]
      (is (= :success (action/execute action env))))))

(deftest test-think-action
  (testing "the :think action forwards an llm-request to the gateway-chan"
    (let [gateway-chan (a/chan 1)
          action {:type :think :prompt "What is the meaning of life?"}
          env {:gateway-chan gateway-chan}]
      (action/execute action env)
      (let [expected {:type :llm-request
                      :prompt "What is the meaning of life?"
                      :callback-event :thought-result}
            ;; alts!! with timeout prevents test hangs
            [val port] (a/alts!! [gateway-chan (a/timeout 100)])]
        (is (= gateway-chan port) "A value should be put on the gateway-chan")
        (is (= expected val) "The correct llm-request should be constructed and sent")))))
