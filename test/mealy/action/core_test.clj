(ns mealy.action.core-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]
            [sci.core :as sci]))

(defmethod action/execute :mock-action
  [_ env]
  (:mock-result env))

(deftest test-execute-multimethod
  (testing "execute dispatches based on the :type of the action"
    (let [action {:type :mock-action}
          env {:mock-result :success}]
      (is (= :success (action/execute action env))))))

(deftest test-think-action
  (testing "the :think action puts a :think-request on cell-in-chan"
    (let [cell-in-chan (a/chan 1)
          action {:type :think :prompt "What is the meaning of life?"}
          env {:cell-in-chan cell-in-chan}]
      (action/execute action env)
      (let [expected [:think-request {:prompt "What is the meaning of life?" :complexity :medium}]
            ;; alts!! with timeout prevents test hangs
            [val port] (a/alts!! [cell-in-chan (a/timeout 100)])]
        (is (= cell-in-chan port) "A value should be put on the cell-in-chan")
        (is (= expected val) "The correct think-request should be constructed and sent")))))

(deftest test-eval-action-success
  (testing "the :eval action evaluates valid code and returns success observation"
    (let [in-chan (a/chan 1)
          action {:type :eval :code "(+ 1 2)"}
          env {:cell-in-chan in-chan}]
      (action/execute action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= [:observation {:type :eval-success :result "3" :code "(+ 1 2)"}] val) "The correct observation should be constructed and sent")))))

(deftest test-eval-action-error
  (testing "the :eval action catches errors and returns evaluation-error event"
    (let [in-chan (a/chan 1)
          action {:type :eval :code "(/ 1 0)"}
          env {:cell-in-chan in-chan}]
      (action/execute action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= :evaluation-error (first val)) "The first element should be :evaluation-error")
        (is (string? (:reason (second val))) "The reason should be a string message")
        (is (= "(/ 1 0)" (:code (second val))) "The code should be passed through")))))

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

(deftest test-von-neumann-self-modification-reducer
  (testing "the :eval action allows defining dynamic handlers"
    (let [in-chan (a/chan 1)
          ;; Now that handle-event is a pure function driven by state,
          ;; we evaluate to a map/function that would be added to the cell's handlers
          code "(fn [s e] {:state s :actions []})"
          eval-action {:type :eval :code code}
          env {:cell-in-chan in-chan}]
      (action/execute eval-action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= :eval-success (:type (second val))) "The evaluation should be successful")
        (when (= :eval-error (:type (second val)))
          (println "Eval Error:" (:error (second val))))))))

(deftest test-sci-ctx-evaluates-handlers-and-actions
  (testing "sci-ctx safely evaluates pure Handlers and impure Actions"
    (let [pure-handler-code "(fn [s e] {:state s :actions []})"
          impure-action-code "(require '[mealy.action.core :as action])\n(defmethod action/execute :sci-test-action [_ _] :ok)"]
      (is (fn? (sci/eval-string* action/sci-ctx pure-handler-code)))
      (sci/eval-string* action/sci-ctx impure-action-code)
      (is (= :ok (action/execute {:type :sci-test-action} {}))))))
