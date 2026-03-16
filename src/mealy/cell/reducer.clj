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
      (let [new-state (update state :observations conj event-data)]
        (if (= (:phase new-state) :idle)
          {:state (assoc new-state :phase :evaluating)
           :commands [{:type :llm-request
                       :prompt (prompt/compile-prompt new-state)
                       :complexity :high
                       :callback-event :consent-evaluated}]}
          {:state new-state
           :commands []}))

      :consent-evaluated
      (let [{:keys [consent]} (parse-consent (:response event-data))]
        (if consent
          {:state (assoc state :phase :acting)
           :commands [{:type :execute-action}]}
          {:state (assoc state :phase :idle)
           :commands []}))

      :evaluation-error
      {:state (-> state
                  (assoc :phase :idle)
                  (assoc :last-error (:reason event-data)))
       :commands []}

      {:state state :commands []})))
