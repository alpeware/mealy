(ns mealy.ooda.prompt
  "Pure functions to compile Cell state into a Consent-based LLM evaluation prompt.")

(defn compile-prompt
  "Compiles the Cell's state into a Consent-based LLM evaluation prompt string.
  Expects the state map to contain :aim, :memory, and :observations."
  [{:keys [aim memory observations] :as _state}]
  (str "Consent-based LLM evaluation\n\n"
       "Aim:\n" aim "\n\n"
       "Memory:\n" (pr-str memory) "\n\n"
       "Observations:\n" (pr-str observations)))
