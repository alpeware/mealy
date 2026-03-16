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

(deftest test-eval-action-success
  (testing "the :eval action evaluates valid code and returns success observation"
    (let [in-chan (a/chan 1)
          action {:type :eval :code "(+ 1 2)"}
          env {:cell-in-chan in-chan}]
      (action/execute action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= [:observation {:type :eval-success :result 3}] val) "The correct observation should be constructed and sent")))))

(deftest test-eval-action-error
  (testing "the :eval action catches errors and returns error observation"
    (let [in-chan (a/chan 1)
          action {:type :eval :code "(/ 1 0)"}
          env {:cell-in-chan in-chan}]
      (action/execute action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= :observation (first val)) "The first element should be :observation")
        (is (= :eval-error (:type (second val))) "The type should be :eval-error")
        (is (string? (:error (second val))) "The error should be a string message")))))

(deftest test-von-neumann-self-modification
  (testing "the :eval action allows defining new defmethods for action/execute"
    (let [in-chan (a/chan 1)
          code "(require '[mealy.action.core :as action])\n(defmethod action/execute :new-skill [_ env] (clojure.core.async/put! (:cell-in-chan env) [:observation {:type :new-skill-success}]))"
          eval-action {:type :eval :code code}
          env {:cell-in-chan in-chan}]
      ;; First, evaluate the code to define the new skill
      (action/execute eval-action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= :eval-success (:type (second val))) "The evaluation should be successful"))

      ;; Now, try to use the new skill
      (let [new-skill-action {:type :new-skill}]
        (action/execute new-skill-action env)
        (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
          (is (= in-chan port) "A value should be put on the cell-in-chan")
          (is (= [:observation {:type :new-skill-success}] val) "The new skill should execute successfully"))))))
