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
              (fn [{:keys [prompt]} {:keys [cell-in-chan]}]
                (a/put! cell-in-chan [:think-request {:prompt prompt}]))
              {:doc "Delegates a cognitive task to the LLM. Expects a :prompt string."}))

;; Legacy global SCI context — kept for backward compatibility with existing
;; tests and the JVM store restore-cell path.  New code should prefer using
;; the cell's own :sci-ctx stored in state.
(def sci-ctx
  "A sandboxed SCI environment exposing mealy.action.core/execute
  and clojure.core.async/put! for dynamic skill acquisition."
  (sci/init {:namespaces {'mealy.action.core {'execute execute}
                          'clojure.core.async {'put! a/put!}}}))

(defn register-action-ns!
  "Registers the mealy.action.core/execute multimethod into a given SCI context.
  Call this after creating a cell's SCI context to enable :eval actions
  that reference action/execute."
  [ctx]
  (swap! (:env ctx) assoc-in [:namespaces 'mealy.action.core 'execute] execute)
  ctx)

(.addMethod execute :eval
            (with-meta
              (fn [{:keys [code]} {:keys [cell-in-chan cell-sci-ctx]}]
                ;; Prefer the cell-local SCI context; fall back to global.
                (let [ctx (or cell-sci-ctx sci-ctx)]
                  (try
                    (let [result (sci/eval-string* ctx code)]
                      (a/put! cell-in-chan [:observation {:type :eval-success :result result :code code}]))
                    (catch Exception e
                      (a/put! cell-in-chan [:observation {:type :eval-error :error (.getMessage e)}])))))
              {:doc "Evaluates sandboxed Clojure code dynamically for skill acquisition."}))
