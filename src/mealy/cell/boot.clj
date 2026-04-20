(ns mealy.cell.boot
  "Cell bootstrap loader. Evaluates the OODA cognitive pipeline
   into a Cell's SCI context, and provides namespace registration
   for the bootstrap script's dependencies (llm, prompt, mitosis)."
  (:require [clojure.java.io :as io]
            [mealy.action.core :as action]
            [mealy.cell.mitosis :as mitosis]
            [mealy.cell.reducer :as reducer]
            [mealy.intelligence.llm :as llm]
            [mealy.ooda.prompt :as prompt]
            [sci.core :as sci]))

(defn register-bootstrap-ns!
  "Registers the namespaces required by the bootstrap script into a
   Cell's SCI context. Must be called before eval'ing bootstrap.clj.
   Injects:
   - mealy.cell.reducer/handle-event  (the multimethod to extend)
   - mealy.action.core/execute        (for skill acquisition)
   - mealy.intelligence.llm/*         (LLM orchestration helpers)
   - mealy.ooda.prompt/*              (prompt compilation)
   - mealy.cell.mitosis/*             (complexity + division)"
  [ctx]
  ;; Reducer handle-event multimethod
  (reducer/register-reducer-ns! ctx)
  ;; Action execute multimethod
  (action/register-action-ns! ctx)
  ;; Intelligence LLM helpers
  (swap! (:env ctx) assoc-in [:namespaces 'mealy.intelligence.llm]
         {'route-llm-request     llm/route-llm-request
          'reroute-on-failure    llm/reroute-on-failure
          'parse-llm-response    llm/parse-llm-response
          'update-provider-state llm/update-provider-state
          'parse-consent         llm/parse-consent
          'extract-edn-array     llm/extract-edn-array})
  ;; Prompt compilation
  (swap! (:env ctx) assoc-in [:namespaces 'mealy.ooda.prompt]
         {'sociocratic-system-prompt    prompt/sociocratic-system-prompt
          'agentic-system-prompt        prompt/agentic-system-prompt
          'code-gen-system-prompt       prompt/code-gen-system-prompt
          'code-review-system-prompt    prompt/code-review-system-prompt
          'compile-state-context        prompt/compile-state-context
          'compile-bootstrap-context    prompt/compile-bootstrap-context
          'compile-prompt               prompt/compile-prompt
          'compile-agentic-prompt       prompt/compile-agentic-prompt
          'compile-policy-evaluation-prompt prompt/compile-policy-evaluation-prompt
          'compile-tap-system-prompt    prompt/compile-tap-system-prompt
          'compile-think-system-prompt  prompt/compile-think-system-prompt})
  ;; Mitosis helpers
  (swap! (:env ctx) assoc-in [:namespaces 'mealy.cell.mitosis]
         {'threshold-reached? mitosis/threshold-reached?
          'divide              mitosis/divide})
  ctx)

(defn load-bootstrap-source
  "Loads the canonical bootstrap.clj from the classpath.
   Returns the source code string, or nil if not found."
  []
  (when-let [res (io/resource "bootstrap.clj")]
    (slurp res)))

(defn boot-cell!
  "Evaluates the bootstrap script into a Cell's SCI context.
   This installs the OODA cognitive pipeline handlers by defining
   methods on the handle-event multimethod.

   If `bootstrap-source` is provided, uses that source code.
   Otherwise loads the canonical resources/bootstrap.clj from the classpath."
  ([ctx]
   (boot-cell! ctx (load-bootstrap-source)))
  ([ctx bootstrap-source]
   (when (and ctx bootstrap-source)
     (register-bootstrap-ns! ctx)
     (sci/eval-string* ctx bootstrap-source))
   ctx))
