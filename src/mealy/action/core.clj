(ns mealy.action.core
  "The Action Registry Foundation. Provides an extensible execution router
  for Von Neumann style action execution and self-modification."
  (:require [clojure.core.async :as a]
            [sci.core :as sci]))

(defmulti execute
  "Extensible execution router. Dispatches on the `:type` of the action map.
  Receives the `action` map and an `env` map containing system contexts
  (e.g., channels like `:gateway-chan` or `:cell-in-chan`)."
  (fn [action _env]
    (:type action)))

(.addMethod execute :think
            (with-meta
              (fn [{:keys [prompt]} {:keys [out-chan]}]
                (a/put! out-chan {:type :execute-action
                                  :action {:type :llm-request
                                           :prompt prompt
                                           :callback-event :thought-result}}))
              {:doc "Delegates a cognitive task to the LLM. Expects a :prompt string."}))

(.addMethod execute :llm-request
            (with-meta
              (fn [{:keys [prompt estimated-tokens complexity callback-event]}
                   {:keys [router-chan cell-in-chan]}]
                (let [reply-chan (a/chan 1)]
                  (a/put! router-chan {:type :evaluate
                                       :prompt prompt
                                       :estimated-tokens estimated-tokens
                                       :complexity complexity
                                       :reply-chan reply-chan})
                  (a/go
                    (let [response (a/<! reply-chan)]
                      (if (or (= (:status response) :error) (:error response))
                        (a/>! cell-in-chan [:evaluation-error {:reason (:reason response)}])
                        (a/>! cell-in-chan [callback-event {:response (:response response)}]))))))
              {:doc "Sends an LLM request to the router and returns the observation to the cell's input channel."}))

(def sci-ctx
  "A sandboxed SCI environment exposing mealy.action.core/execute
  and clojure.core.async/put! for dynamic skill acquisition."
  (sci/init {:namespaces {'mealy.action.core {'execute execute}
                          'mealy.cell.reducer {'handle-event @(requiring-resolve 'mealy.cell.reducer/handle-event)}
                          'clojure.core.async {'put! a/put!}}}))

(.addMethod execute :eval
            (with-meta
              (fn [{:keys [code]} {:keys [cell-in-chan]}]
                (try
                  (let [result (sci/eval-string* sci-ctx code)]
                    (a/put! cell-in-chan [:observation {:type :eval-success :result result :code code}]))
                  (catch Exception e
                    (a/put! cell-in-chan [:observation {:type :eval-error :error (.getMessage e)}]))))
              {:doc "Evaluates sandboxed Clojure code dynamically for skill acquisition."}))
