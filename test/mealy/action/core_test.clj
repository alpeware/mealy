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
  (testing "the :think action forwards an llm-request to the out-chan"
    (let [out-chan (a/chan 1)
          action {:type :think :prompt "What is the meaning of life?"}
          env {:out-chan out-chan}]
      (action/execute action env)
      (let [expected {:type :execute-action
                      :action {:type :llm-request
                               :messages [{:role "user" :content "What is the meaning of life?"}]
                               :callback-event :thought-result}}
            ;; alts!! with timeout prevents test hangs
            [val port] (a/alts!! [out-chan (a/timeout 100)])]
        (is (= out-chan port) "A value should be put on the out-chan")
        (is (= expected val) "The correct llm-request should be constructed and sent")))))

(deftest test-eval-action-success
  (testing "the :eval action evaluates valid code and returns success observation"
    (let [in-chan (a/chan 1)
          action {:type :eval :code "(+ 1 2)"}
          env {:cell-in-chan in-chan}]
      (action/execute action env)
      (let [[val port] (a/alts!! [in-chan (a/timeout 100)])]
        (is (= in-chan port) "A value should be put on the cell-in-chan")
        (is (= [:observation {:type :eval-success :result 3 :code "(+ 1 2)"}] val) "The correct observation should be constructed and sent")))))

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

(deftest test-llm-request-action-success
  (testing "the :llm-request action forwards evaluation to router and puts success on cell-in-chan"
    (let [router-chan (a/chan 1)
          cell-in-chan (a/chan 1)
          action {:type :llm-request
                  :messages [{:role "user" :content "test-prompt"}]
                  :callback-event :my-event
                  :estimated-tokens 50
                  :complexity :low}
          env {:router-chan router-chan :cell-in-chan cell-in-chan}]
      (action/execute action env)
      ;; Mock the router behavior
      (let [[router-cmd port] (a/alts!! [router-chan (a/timeout 100)])]
        (is (= router-chan port))
        (is (= :evaluate (:type router-cmd)))
        (is (= [{:role "user" :content "test-prompt"}] (:messages router-cmd)))
        (is (= 50 (:estimated-tokens router-cmd)))
        (is (= :low (:complexity router-cmd)))
        ;; Send back a successful response on the reply-chan
        (a/>!! (:reply-chan router-cmd) {:response "some raw llm response"}))

      ;; Read the resulting observation from the cell's input channel
      (let [[observation port] (a/alts!! [cell-in-chan (a/timeout 100)])]
        (is (= cell-in-chan port))
        (is (= :my-event (first observation)))
        (let [obs-data (second observation)]
          (is (= "some raw llm response" (:response obs-data))))))))

(deftest test-llm-request-action-error
  (testing "the :llm-request action handles router errors gracefully"
    (let [router-chan (a/chan 1)
          cell-in-chan (a/chan 1)
          action {:type :llm-request
                  :messages [{:role "user" :content "test-prompt"}]
                  :callback-event :my-event}
          env {:router-chan router-chan :cell-in-chan cell-in-chan}]
      (action/execute action env)
      ;; Mock the router behavior sending an error
      (let [[router-cmd port] (a/alts!! [router-chan (a/timeout 100)])]
        (is (= router-chan port))
        (is (= :evaluate (:type router-cmd)))
        (a/>!! (:reply-chan router-cmd) {:status :error :reason :no-available-provider}))

      ;; Read the resulting error observation from the cell's input channel
      (let [[observation port] (a/alts!! [cell-in-chan (a/timeout 100)])]
        (is (= cell-in-chan port))
        (is (= :evaluation-error (first observation)))
        (let [obs-data (second observation)]
          (is (= :no-available-provider (:reason obs-data))))))))

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
