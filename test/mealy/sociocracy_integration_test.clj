(ns mealy.sociocracy-integration-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]
            [mealy.cell.core :as cell]
            [mealy.runtime.jvm.bus :as bus]
            [mealy.runtime.jvm.core :as rcore]
            [mealy.runtime.jvm.store :as store]))

(deftest e2e-policy-proposal-and-execution-test
  (testing "End-to-end Sociocratic self-modification loop using JVM Runtime"
    (let [initial-state (cell/make-cell "Learn to echo" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          in-chan (async/chan 100)
          out-chan (async/chan 100)
          test-chan (async/chan 100)
          temp-dir (java.nio.file.Files/createTempDirectory "mealy-sociocracy-test" (into-array java.nio.file.attribute.FileAttribute []))
          dir-path (.getAbsolutePath (.toFile temp-dir))
          event-store (store/->JVMEventStore {:dir-path dir-path})
          event-bus (bus/make-bus)
          cell-id :sociocracy-cell

          ;; Save the original :http-request method to restore later
          original-http-request-method (get-method action/execute :http-request)]

      (try
        ;; Mock the LLM routing by overriding the :http-request action
        ;; so that it immediately returns the mocked consent observation
        ;; to the cell's input channel, completely bypassing actual network IO.
        (remove-method action/execute :http-request)
        (defmethod action/execute :http-request
          [{:keys [callback-event]} env]
          (let [cell-in-chan (or (:cell-in-chan env) (:in-chan env))
                json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"CONSENT: This code looks safe.\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"]
            (async/put! cell-in-chan [callback-event {:response {:status 200 :body json-body}}])))

        ;; Start the node with the channels provided in the environment.
        ;; Using an active worker pool to process out-chan commands.
        (rcore/start-node event-store event-bus cell-id initial-state in-chan out-chan
                          {:workers 1
                           :cell-in-chan in-chan
                           :test-chan test-chan})

        ;; Step 1: The Proposal
        ;; Injecting a proposed policy to define a new skill `:echo-test`.
        ;; The skill will put a message on the `:test-chan` when executed.
        (let [proposal-code "(require '[mealy.action.core :as action])\n(defmethod action/execute :echo-test [_ env] (clojure.core.async/put! (:test-chan env) [:observation {:type :echo-success}]))"]
          (async/>!! in-chan [:propose-policy {:code proposal-code}]))

        ;; Step 2: The Wait
        ;; The node processes the OODA loop:
        ;; 1. Reducer handles :propose-policy -> yields :http-request.
        ;; 2. Worker pool runs mocked :http-request -> puts :policy-consent-evaluated on in-chan.
        ;; 3. Reducer handles :policy-consent-evaluated -> yields :eval action.
        ;; 4. Worker pool runs :eval -> runs sci/eval-string*, returning :eval-success to in-chan.
        (async/<!! (async/timeout 100))
        (async/<!! (async/timeout 100))
        (async/<!! (async/timeout 100))
        (async/<!! (async/timeout 100))

        ;; Step 3: The Execution
        ;; The new skill is now registered!
        ;; We manually yield the command by sending it to out-chan for the worker pool to execute.
        (async/>!! out-chan {:type :echo-test})

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
          (doseq [f (reverse (file-seq (.toFile temp-dir)))]
            (.delete f))
          ;; Restore the original :http-request action method
          (remove-method action/execute :http-request)
          (when original-http-request-method
            (.addMethod action/execute :http-request original-http-request-method))
          ;; Remove the dynamically created :echo-test method to avoid test pollution
          (remove-method action/execute :echo-test))))))
