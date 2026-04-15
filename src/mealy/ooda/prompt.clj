(ns mealy.ooda.prompt
  "Pure functions to compile Cell state into LLM evaluation prompts."
  (:require [clojure.string :as str]
            [mealy.action.core :as action]
            [mealy.subscription.core :as sub]))

(def sociocratic-system-prompt
  "The hardcoded deterministic cognitive routing engine persona."
  "You are the deterministic cognitive routing engine for an autonomous agent. Your ONLY purpose is to evaluate the user's provided State and Proposed Action against the Aim, Memory, and Policies. You do not converse. You do not offer help. If the Proposed Action does not critically harm the Aim, violate Memory constraints, or contravene any Policy, you MUST output exactly: 'CONSENT: [brief reason]'. If it violates a critical constraint or Policy, output exactly: 'OBJECTION: [reason]'.")

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

(defn get-available-subscriptions
  "Dynamically inspects the start-subscription multimethod to extract
  the currently registered subscription types and their docstrings."
  []
  (let [methods-map (dissoc (methods sub/start-subscription) :default)
        descriptions (for [[dispatch-val method-fn] methods-map
                           :let [docstring (:doc (meta method-fn))
                                 desc (or docstring "No description available.")]]
                       (str "- " dispatch-val ": " desc))]
    (str "Available Subscriptions:\n" (str/join "\n" descriptions))))

(defn compile-prompt
  "Compiles the Cell's state into a Consent-based LLM evaluation prompt string.
  Expects the state map to contain :aim, :memory, :observations, and :policies."
  [{:keys [aim memory observations policies] :as _state}]
  (str "Consent-based LLM evaluation\n\n"
       "Aim:\n" aim "\n\n"
       "Policies:\n"
       (if (seq policies)
         (str/join "\n" (map-indexed (fn [i p] (str (inc i) ". " p)) policies))
         "(none)")
       "\n\n"
       "Memory:\n" (pr-str memory) "\n\n"
       "Observations:\n" (pr-str observations) "\n\n"
       (get-available-tools) "\n\n"
       (get-available-subscriptions)))

(defn compile-policy-evaluation-prompt
  "Compiles an LLM prompt for the cell to evaluate a proposed policy change
  against its current Aim, Memory, and existing Policies."
  [state proposed-policy]
  (str "A policy change has been proposed for your consent.\n\n"
       "Current Aim:\n" (:aim state) "\n\n"
       "Current Policies:\n"
       (if (seq (:policies state))
         (str/join "\n" (map-indexed (fn [i p] (str (inc i) ". " p)) (:policies state)))
         "(none)")
       "\n\n"
       "Proposed New Policy:\n" proposed-policy "\n\n"
       "Evaluate whether this proposed policy is consistent with the Aim and "
       "does not contradict existing Policies.\n"
       "If acceptable, respond with: CONSENT: [brief reason]\n"
       "If problematic, respond with: OBJECTION: [reason]"))

(defn compile-tap-system-prompt
  "Compiles a system prompt for a tap interaction, giving the LLM context
   about the cell's aim, current time, phase, policies, subscriptions,
   and recent observations."
  [state]
  (let [aim (:aim state)
        current-time (get-in state [:memory :current-time] "unknown")
        phase (name (:phase state))
        policies (:policies state)
        subs (:subscriptions state)
        recent-obs (take-last 5 (:observations state))]
    (str "You are an autonomous Mealy cell. A human colleague is checking in with you.\n\n"
         "Your aim: " aim "\n"
         "Current time: " current-time "\n"
         "Current phase: " phase "\n"
         (when (seq policies)
           (str "Active policies:\n"
                (str/join "\n" (map-indexed (fn [i p] (str (inc i) ". " p)) policies))
                "\n"))
         "Current subscriptions: "
         (if (seq subs)
           (str/join ", " (map name subs))
           "(none)")
         "\n"
         (when (seq recent-obs)
           (str "Recent observations:\n"
                (str/join "\n" (map pr-str recent-obs))
                "\n"))
         "\nRespond concisely and helpfully. Be conversational.")))
