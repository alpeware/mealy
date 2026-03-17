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

(defn handle-event
  "Pure function that takes a cell state and an event, and returns a map
  containing the updated :state and a vector of :commands."
  [state event]
  (let [[event-type event-data] event]
    (case event-type
      :observation
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
               :commands [reflex-match]}
              {:state (assoc new-state :phase :evaluating)
               :commands [{:type :execute-action
                           :action {:type :llm-request
                                    :prompt (prompt/compile-prompt new-state)
                                    :complexity :high
                                    :callback-event :consent-evaluated}}]}))
          {:state new-state
           :commands []}))

      :consent-evaluated
      (let [{:keys [consent]} (parse-consent (:response event-data))]
        (if consent
          {:state (assoc state :phase :acting)
           :commands [{:type :execute-action}]}
          {:state (assoc state :phase :idle)
           :commands []}))

      :propose-policy
      (let [code (:code event-data)
            new-state (update-in state [:memory :proposed-policies] (fnil conj []) code)]
        {:state (assoc new-state :phase :evaluating)
         :commands [{:type :execute-action
                     :action {:type :llm-request
                              :prompt (prompt/compile-prompt new-state)
                              :complexity :high
                              :callback-event :policy-consent-evaluated}}]})

      :policy-consent-evaluated
      (let [{:keys [consent]} (parse-consent (:response event-data))
            policies (get-in state [:memory :proposed-policies] [])
            policy (first policies)
            rem-policies (vec (rest policies))
            new-state (assoc-in state [:memory :proposed-policies] rem-policies)]
        (if consent
          {:state (assoc new-state :phase :acting)
           :commands [{:type :execute-action
                       :action {:type :eval
                                :code policy}}]}
          {:state (assoc new-state :phase :idle)
           :commands []}))

      :evaluation-error
      {:state (-> state
                  (assoc :phase :idle)
                  (assoc :last-error (:reason event-data)))
       :commands []}

      {:state state :commands []})))
