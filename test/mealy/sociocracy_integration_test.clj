(ns mealy.sociocracy-integration-test
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]
            [mealy.cell.core :as cell]
            [mealy.runtime.jvm.bus :as bus]
            [mealy.runtime.jvm.core :as rcore]
            [mealy.runtime.jvm.store :as store]))

(deftest e2e-proposal-and-execution-test
  (testing "End-to-end Sociocratic self-modification loop using JVM Runtime"
    (let [proposal-code "(require '[mealy.action.core :as action])\n(defmethod action/execute :echo-test [_ env] (clojure.core.async/put! (:test-chan env) [:observation {:type :echo-success}]))"
          initial-state (cell/make-cell "Learn to echo" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 100000 :complexity :high}}})
          _ (action/register-action-ns! (:sci-ctx initial-state))
          in-chan (async/chan 100)
          out-chan (async/chan 100)
          test-chan (async/chan 100)
          temp-dir (java.nio.file.Files/createTempDirectory "mealy-sociocracy-test" (into-array java.nio.file.attribute.FileAttribute []))
          dir-path (.getAbsolutePath (.toFile temp-dir))
          event-store (store/->JVMEventStore {:dir-path dir-path})
          event-bus (bus/make-bus)
          cell-id :sociocracy-cell

          ;; Save original methods to restore later
          original-http-request-method (get-method action/execute :http-request)
          original-dry-run-method (get-method action/execute :dry-run-eval)]

      (try
        ;; Mock the LLM routing by overriding the :http-request action.
        ;; The pipeline is: :proposal → :proposal-evaluated → :code-generated → :dry-run → :code-review-evaluated → :eval.
        ;; Each :http-request callback-event determines what phase we're returning for.
        (remove-method action/execute :http-request)
        (defmethod action/execute :http-request
          [{:keys [callback-event]} env]
          (let [cell-in-chan (or (:cell-in-chan env) (:in-chan env))
                body (case callback-event
                       ;; Phase 1: Consent to evaluate the proposal
                       :proposal-evaluated
                       "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"CONSENT: This code looks safe.\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
                       ;; Phase 2: Return generated code
                       :code-generated
                       (json/generate-string
                        {:candidates [{:content {:parts [{:text proposal-code}]}}]
                         :usageMetadata {:totalTokenCount 20}})
                       ;; Phase 3: Code review consent
                       :code-review-evaluated
                       "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"CONSENT: Code review passed.\"}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}"
                       ;; Default: generic consent
                       "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"CONSENT\"}]}}],\"usageMetadata\":{\"totalTokenCount\":5}}")]
            (async/put! cell-in-chan [callback-event {:response {:status 200 :body body}}])))

        ;; Mock :dry-run-eval to pass through immediately with success
        (remove-method action/execute :dry-run-eval)
        (defmethod action/execute :dry-run-eval
          [{:keys [code]} env]
          (let [cell-in-chan (or (:cell-in-chan env) (:in-chan env))]
            (async/put! cell-in-chan [:dry-run-success {:code code}])))

        ;; Start the node with the channels provided in the environment.
        (rcore/start-node event-store event-bus cell-id initial-state in-chan out-chan
                          {:workers 1
                           :cell-in-chan in-chan
                           :cell-sci-ctx (:sci-ctx initial-state)
                           :test-chan test-chan})

        ;; Step 1: The Proposal
        ;; Injecting a proposal to define a new skill `:echo-test`.
        (async/>!! in-chan [:proposal {:prompt proposal-code}])

        ;; Step 2: The Wait
        ;; The node processes the full pipeline:
        ;; 1. :proposal → yields :http-request (callback: :proposal-evaluated)
        ;; 2. :proposal-evaluated → yields :http-request (callback: :code-generated)
        ;; 3. :code-generated → yields :dry-run-eval
        ;; 4. :dry-run-success → yields :http-request (callback: :code-review-evaluated)
        ;; 5. :code-review-evaluated → yields :eval
        ;; 6. :eval → runs sci/eval-string*, :eval-success observation on in-chan
        (async/<!! (async/timeout 500))
        (async/<!! (async/timeout 500))
        (async/<!! (async/timeout 500))
        (async/<!! (async/timeout 500))

        ;; Step 3: The Execution
        ;; The new skill is now registered! Execute it via out-chan.
        (async/>!! out-chan {:type :echo-test})

        ;; Step 4: The Proof
        (let [[val port] (async/alts!! [test-chan (async/timeout 3000)])]
          (is (= test-chan port) "A value should be placed on test-chan by the new skill")
          (is (= [:observation {:type :echo-success}] val) "The new skill should execute successfully and return the expected observation"))

        (finally
          ;; Cleanup
          (async/close! in-chan)
          (async/close! out-chan)
          (async/close! test-chan)
          (doseq [f (reverse (file-seq (.toFile temp-dir)))]
            (.delete f))
          ;; Restore original methods
          (remove-method action/execute :http-request)
          (when original-http-request-method
            (.addMethod action/execute :http-request original-http-request-method))
          (remove-method action/execute :dry-run-eval)
          (when original-dry-run-method
            (.addMethod action/execute :dry-run-eval original-dry-run-method))
          ;; Remove the dynamically created :echo-test method to avoid test pollution
          (when (get-method action/execute :echo-test)
            (remove-method action/execute :echo-test)))))))
