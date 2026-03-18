(ns mealy.sociocracy-integration-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]
            [mealy.cell.core :as cell]
            [mealy.shell.core :as shell]))

(deftest e2e-policy-proposal-and-execution-test
  (testing "End-to-end Sociocratic self-modification loop"
    (let [initial-state (cell/make-cell "Learn to echo" {})
          in-chan (async/chan 100)
          out-chan (async/chan 100)
          test-chan (async/chan 100)
          temp-log (java.io.File/createTempFile "events" ".log")
          log-path (.getAbsolutePath temp-log)

          ;; Save the original :llm-request method to restore later
          original-llm-request-method (get-method action/execute :llm-request)]

      (try
        ;; Mock the LLM routing by overriding the :llm-request action
        ;; so that it immediately returns the mocked consent observation
        ;; to the cell's input channel, completely bypassing the actual router.
        (remove-method action/execute :llm-request)
        (defmethod action/execute :llm-request
          [{:keys [callback-event]} {:keys [cell-in-chan]}]
          ;; we expect callback-event to be :policy-consent-evaluated
          (async/put! cell-in-chan [callback-event {:response "CONSENT: This code looks safe."}]))

        ;; Start the shell with the channels provided in the environment.
        ;; Using an active worker pool to process out-chan commands.
        (shell/start-shell initial-state in-chan out-chan
                           {:event-log-path log-path
                            :workers 1
                            :cell-in-chan in-chan
                            :test-chan test-chan})

        ;; Step 1: The Proposal
        ;; Injecting a proposed policy to define a new skill `:echo-test`.
        ;; The skill will put a message on the `:test-chan` when executed.
        (let [proposal-code "(require '[mealy.action.core :as action])\n(defmethod action/execute :echo-test [_ env] (clojure.core.async/put! (:test-chan env) [:observation {:type :echo-success}]))"]
          (async/>!! in-chan [:propose-policy {:code proposal-code}]))

        ;; Step 2: The Wait
        ;; The shell processes the OODA loop:
        ;; 1. Reducer handles :propose-policy -> yields :llm-request.
        ;; 2. Worker pool runs mocked :llm-request -> puts :policy-consent-evaluated on in-chan.
        ;; 3. Reducer handles :policy-consent-evaluated -> yields :eval action.
        ;; 4. Worker pool runs :eval -> runs sci/eval-string*, returning :eval-success to in-chan.
        (async/<!! (async/timeout 1000))

        ;; Step 3: The Execution
        ;; The new skill is now registered!
        ;; We manually yield the command by sending it to out-chan for the worker pool to execute.
        (async/>!! out-chan {:type :execute-action :action {:type :echo-test}})

        ;; Step 4: The Proof
        ;; Read from the test-chan to assert that the new skill executed successfully.
        (let [[val port] (async/alts!! [test-chan (async/timeout 1000)])]
          (is (= test-chan port) "A value should be placed on test-chan by the new skill")
          (is (= [:observation {:type :echo-success}] val) "The new skill should execute successfully and return the expected observation"))

        (finally
          ;; Cleanup
          (async/close! in-chan)
          (async/close! out-chan)
          (async/close! test-chan)
          (.delete temp-log)
          ;; Restore the original :llm-request action method
          (remove-method action/execute :llm-request)
          (.addMethod action/execute :llm-request original-llm-request-method)
          ;; Remove the dynamically created :echo-test method to avoid test pollution
          (remove-method action/execute :echo-test))))))
