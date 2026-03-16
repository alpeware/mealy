(ns mealy.action.core
  "The Action Registry Foundation. Provides an extensible execution router
  for Von Neumann style action execution and self-modification."
  (:require [clojure.core.async :as a]))

(defmulti execute
  "Extensible execution router. Dispatches on the `:type` of the action map.
  Receives the `action` map and an `env` map containing system contexts
  (e.g., channels like `:gateway-chan` or `:cell-in-chan`)."
  (fn [action _env]
    (:type action)))

(defmethod execute :think
  [{:keys [prompt]} {:keys [gateway-chan]}]
  (a/put! gateway-chan {:type :llm-request
                        :prompt prompt
                        :callback-event :thought-result}))
