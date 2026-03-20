(ns mealy.ooda.prompt
  "Pure functions to compile Cell state into a Consent-based LLM evaluation prompt."
  (:require [clojure.string :as str]
            [mealy.action.core :as action]))

(def sociocratic-system-prompt
  "The hardcoded deterministic cognitive routing engine persona."
  "You are the deterministic cognitive routing engine for an autonomous agent. Your ONLY purpose is to evaluate the user's provided State and Proposed Policy. You do not converse. You do not offer help. If the Proposed Policy does not critically harm the Aim or violate Memory, you MUST output exactly: 'CONSENT: [brief reason]'. If it violates a critical constraint, output exactly: 'OBJECTION: [reason]'.")

(defn get-available-tools
  "Dynamically inspects the mealy.action.core/execute multimethod to extract
  the currently registered tools and their docstrings."
  []
  (let [methods-map (methods action/execute)
        tool-descriptions (for [[dispatch-val method-fn] methods-map
                                :let [docstring (:doc (meta method-fn))
                                      desc (or docstring "No description available.")]]
                            (str "- " dispatch-val ": " desc))]
    (str "Available Tools:\n" (str/join "\n" tool-descriptions))))

(defn compile-prompt
  "Compiles the Cell's state into a Consent-based LLM evaluation prompt string.
  Expects the state map to contain :aim, :memory, and :observations."
  [{:keys [aim memory observations] :as _state}]
  (str "Consent-based LLM evaluation\n\n"
       "Aim:\n" aim "\n\n"
       "Memory:\n" (pr-str memory) "\n\n"
       "Observations:\n" (pr-str observations) "\n\n"
       (get-available-tools)))
