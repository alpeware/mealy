(ns mealy.cell.reducer
  "The pure Sans-IO Mealy machine reducer."
  (:require [clojure.string :as str]
            [mealy.ooda.prompt :as prompt]))

(defn parse-consent
  "Pure function to parse the LLM's response string.
  Returns a map containing :consent (boolean) and the original :response."
  [response]
  (let [upper (str/upper-case response)
        consent? (and (str/includes? upper "CONSENT")
                      (not (str/includes? upper "OBJECTION")))]
    {:consent consent?
     :response response}))

(defn handle-observation
  "Handles an :observation event, optionally triggering a reflex or transitioning to :evaluating."
  [state [_ event-data]]
  (let [state-with-obs (update state :observations conj event-data)
        new-state (if (and (= (:type event-data) :eval-success)
                           (:code event-data))
                    (update-in state-with-obs [:memory :active-policies] (fnil conj []) (:code event-data))
                    state-with-obs)]
    (if (= (:phase new-state) :idle)
      (let [reflexes (get-in state [:memory :reflexes])
            reflex-match (or (get reflexes (:type event-data))
                             (get reflexes event-data))]
        (if reflex-match
          {:state new-state
           :actions [reflex-match]}
          {:state (assoc new-state :phase :evaluating)
           :actions [{:type :llm-request
                      :messages [{:role "system" :content prompt/sociocratic-system-prompt}
                                 {:role "user" :content (prompt/compile-prompt new-state)}]
                      :complexity :high
                      :callback-event :consent-evaluated}]}))
      {:state new-state
       :actions []})))

(defn handle-consent-evaluated
  "Handles the LLM response to a consent evaluation."
  [state [_ event-data]]
  (let [{:keys [consent]} (parse-consent (:response event-data))]
    (if consent
      {:state (assoc state :phase :acting)
       :actions []}
      {:state (assoc state :phase :idle)
       :actions []})))

(defn handle-propose-policy
  "Handles a request to propose a new policy, transitioning to :evaluating to seek consent."
  [state [_ event-data]]
  (let [code (:code event-data)
        new-state (update-in state [:memory :proposed-policies] (fnil conj []) code)]
    {:state (assoc new-state :phase :evaluating)
     :actions [{:type :llm-request
                :messages [{:role "system" :content prompt/sociocratic-system-prompt}
                           {:role "user" :content (prompt/compile-prompt new-state)}]
                :complexity :high
                :callback-event :policy-consent-evaluated}]}))

(defn handle-policy-consent-evaluated
  "Handles the LLM response to a policy proposal consent evaluation."
  [state [_ event-data]]
  (let [{:keys [consent]} (parse-consent (:response event-data))
        policies (get-in state [:memory :proposed-policies] [])
        policy (first policies)
        rem-policies (vec (rest policies))
        new-state (assoc-in state [:memory :proposed-policies] rem-policies)]
    (if consent
      {:state (assoc new-state :phase :acting)
       :actions [{:type :eval
                  :code policy}]}
      {:state (assoc new-state :phase :idle)
       :actions []})))

(defn handle-evaluation-error
  "Handles an error during evaluation, transitioning to :idle."
  [state [_ event-data]]
  {:state (-> state
              (assoc :phase :idle)
              (assoc :last-error (:reason event-data)))
   :actions []})

(def default-handlers
  "The built-in set of pure handlers for standard mealy actions."
  {:observation handle-observation
   :consent-evaluated handle-consent-evaluated
   :propose-policy handle-propose-policy
   :policy-consent-evaluated handle-policy-consent-evaluated
   :evaluation-error handle-evaluation-error})

(defn handle-event
  "Pure evaluation loop that routes incoming events to the appropriate handler function
  defined within the Cell's `:handlers` state registry, falling back to `default-handlers`.
  Returns a map containing the updated `:state` and a vector of `:actions`."
  [state [event-type _ :as event]]
  (let [handler (or (get (:handlers state) event-type)
                    (get default-handlers event-type))]
    (if handler
      (handler state event)
      {:state state :actions []})))
