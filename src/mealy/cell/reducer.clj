(ns mealy.cell.reducer
  "The pure Sans-IO Mealy machine reducer."
  (:require [clojure.string :as str]))

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
      :observation {:state (update state :observations conj event-data)
                    :commands []}
      {:state state :commands []})))
