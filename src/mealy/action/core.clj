(ns mealy.action.core
  "The Action Registry Foundation. Provides an extensible execution router
  for Von Neumann style action execution and self-modification."
  (:require [clojure.core.async :as a]
            [clojure.set :as set]
            [mealy.action.spec :as spec]
            [sci.core :as sci]))

(defmulti execute
  "Extensible execution router. Dispatches on the `:type` of the action map.
  Receives the `action` map and an `env` map containing system contexts
  (e.g., channels like `:gateway-chan` or `:cell-in-chan`)."
  (fn [action _env]
    (:type action)))

(.addMethod execute :think
            (with-meta
              (fn [{:keys [prompt complexity]} {:keys [cell-in-chan]}]
                (a/put! cell-in-chan [:think-request {:prompt prompt :complexity (or complexity :medium)}]))
              {:doc "Think: delegates a cognitive query to the LLM. Params: :prompt (string), :complexity (optional, :low/:medium/:high, default :medium). Example: {:type :think :prompt \"What should I do next?\" :complexity :high}"}))

(.addMethod execute :propose
            (with-meta
              (fn [{:keys [prompt]} {:keys [cell-in-chan]}]
                (a/put! cell-in-chan [:proposal {:prompt prompt}]))
              {:doc "Propose: triggers the sociocratic code-gen pipeline (consent → generate → dry-run → review → eval). Params: :prompt (string describing what code to write). Example: {:type :propose :prompt \"Write a handler for :rss-check that fetches an RSS feed\"}"}))

(.addMethod execute :inject-event
            (with-meta
              (fn [{:keys [event]} {:keys [cell-in-chan]}]
                (a/put! cell-in-chan event))
              {:doc "Inject-event: puts an event vector directly onto the cell's input channel. Params: :event (vector [event-type data-map]). Example: {:type :inject-event :event [:observation {:type :note :data \"something happened\"}]}"}))

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
                      (a/put! cell-in-chan [:observation {:type :eval-success :result (pr-str result) :code code}]))
                    (catch Exception e
                      (a/put! cell-in-chan [:evaluation-error {:reason (.getMessage e) :code code}])))))
              {:doc "Eval: evaluates Clojure code in the SCI sandbox. On success emits [:observation {:type :eval-success}], on failure emits [:evaluation-error]. Params: :code (string of valid Clojure). Example: {:type :eval :code \"(defmethod reducer/handle-event :my-event [s e] {:state s :actions []})\"}"}))

(defn- validate-new-handlers
  "After a dry-run eval, detect newly registered handler dispatch values
   on mock-handle vs real-handle. Invoke each new handler with the cell's
   current state and validate the returned :actions against the spec registry.
   Returns nil on success, or a vector of error maps."
  [mock-handle real-handle cell-state]
  (let [new-dispatches (set/difference
                        (set (keys (methods mock-handle)))
                        (set (keys (methods real-handle))))
        seed-state (or cell-state {:aim "dry-run" :memory {} :observations []
                                   :policies [] :phase :idle})]
    (when (seq new-dispatches)
      (let [errors (for [dispatch-val new-dispatches
                         :let [handler (.getMethod mock-handle dispatch-val)
                               result (try
                                        (handler seed-state [dispatch-val {:dry-run true}])
                                        (catch Exception _ nil))]
                         :when (and (map? result) (:actions result))
                         :let [spec-errors (spec/validate-actions (:actions result))]
                         :when spec-errors]
                     {:dispatch-val dispatch-val
                      :errors spec-errors})]
        (when (seq errors)
          (vec errors))))))

(.addMethod execute :dry-run-eval
            (with-meta
              (fn [{:keys [code cell-state]} {:keys [cell-in-chan cell-sci-ctx]}]
                (let [parent-ctx (or cell-sci-ctx sci-ctx)
                      clone-ctx (sci/fork parent-ctx)

                      ;; Create isolated MultiFns to intercept and safely discard `defmethod` side-effects
                      mock-exec (clojure.lang.MultiFn. "execute" (fn [action _env] (:type action)) :default (atom (make-hierarchy)))
                      _ (doseq [[k v] (methods execute)] (.addMethod mock-exec k v))

                      real-handle @(resolve 'mealy.cell.reducer/handle-event)
                      mock-handle (clojure.lang.MultiFn. "handle-event" (fn [_state [event-type _]] event-type) :default (atom (make-hierarchy)))
                      _ (doseq [[k v] (methods real-handle)] (.addMethod mock-handle k v))]

                  ;; Inject the isolated MultiFns into the cloned context
                  (swap! (:env clone-ctx) assoc-in [:namespaces 'mealy.action.core 'execute] mock-exec)
                  (swap! (:env clone-ctx) assoc-in [:namespaces 'mealy.cell.reducer 'handle-event] mock-handle)

                  (try
                    (sci/eval-string* clone-ctx code)
                    ;; Code compiled — now validate action specs on new handlers
                    (if-let [spec-errors (validate-new-handlers mock-handle real-handle cell-state)]
                      (let [error-msg (str "Action spec validation failed:\n"
                                           (pr-str spec-errors))]
                        (a/put! cell-in-chan [:evaluation-error {:reason error-msg :code code}]))
                      (a/put! cell-in-chan [:dry-run-success {:code code}]))
                    (catch Exception e
                      (a/put! cell-in-chan [:evaluation-error {:reason (.getMessage e) :code code}])))))
              {:doc "Dry-run-eval: evaluates code in a forked SCI sandbox to verify it compiles and that
 any new handler methods produce spec-valid actions. On success emits [:dry-run-success {:code ...}].
 On spec failure emits [:evaluation-error]. Params: :code (string), :cell-state (optional, cell's current state for validation). Used internally by the :propose pipeline."}))
