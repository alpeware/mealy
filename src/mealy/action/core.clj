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

(.addMethod execute :propose
            (with-meta
              (fn [{:keys [prompt]} {:keys [cell-in-chan]}]
                (a/put! cell-in-chan [:proposal {:prompt prompt}]))
              {:doc "Proposes new Clojure code to be written and evaluated. Expects a :prompt string detailing what the code should do."}))

(defonce ^:private subscription-registry (atom {}))

(.addMethod execute :start-subscription
            (with-meta
              (fn [{:keys [config]} {:keys [cell-in-chan]}]
                (let [raw-handle ((resolve 'mealy.subscription.core/start-subscription) config {:cell-in-chan cell-in-chan})
                      handle-id (keyword (gensym "sub-"))]
                  (swap! subscription-registry assoc handle-id raw-handle)
                  (a/put! cell-in-chan [:observation {:type :subscription-started :config config :handle handle-id}])))
              {:doc "Starts a subscription that feeds events into the cell. Expects a :config map (e.g. {:type :tick :interval-ms 1000})."}))

(.addMethod execute :stop-subscription
            (with-meta
              (fn [{:keys [config handle]} _env]
                (if-let [raw-handle (get @subscription-registry handle)]
                  (do
                    ((resolve 'mealy.subscription.core/stop-subscription) config raw-handle)
                    (swap! subscription-registry dissoc handle))
                  (println "Warning: cannot stop subscription, handle not found:" handle)))
              {:doc "Stops a running subscription. Expects a :config map and the :handle keyword."}))

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
              {:doc "Evaluates sandboxed Clojure code dynamically for skill acquisition."}))

(.addMethod execute :dry-run-eval
            (with-meta
              (fn [{:keys [code]} {:keys [cell-in-chan cell-sci-ctx]}]
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
                    (a/put! cell-in-chan [:dry-run-success {:code code}])
                    (catch Exception e
                      (a/put! cell-in-chan [:evaluation-error {:reason (.getMessage e) :code code}])))))
              {:doc "Evaluates code in a sandbox fork to catch errors before committing live."}))
