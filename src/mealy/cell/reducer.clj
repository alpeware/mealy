(ns mealy.cell.reducer
  "The pure Sans-IO Mealy machine reducer.")

(defn handle-event
  "Pure function that takes a cell state and an event, and returns a map
  containing the updated :state and a vector of :commands."
  [state event]
  (let [[event-type event-data] event]
    (case event-type
      :observation {:state (update state :observations conj event-data)
                    :commands []}
      {:state state :commands []})))
