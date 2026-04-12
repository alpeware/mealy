(ns mealy.runtime.protocols
  "Protocols for abstract runtimes defining EventBus and EventStore interfaces."
  (:refer-clojure :exclude [get load]))

(defprotocol EventBus
  "Protocol for abstract message passing between agent cells."
  (register [this topic]
    "Registers a topic on the bus.")
  (subscribe [this id topic handler]
    "Subscribes a cell to a specific topic with a given callback handler function.")
  (unsubscribe [this topic id]
    "Unsubscribes a cell from a specific topic.")
  (publish [this topic event]
    "Publishes an event to a specific topic.")
  (query [this] [this topic]
    "Returns the registered topics, or the map of subscribers for a given topic."))

(defprotocol EventStore
  "Protocol for state persistence via event sourcing and snapshots."
  (snapshot [this id data]
    "Saves a snapshot of a cell's state to the store.")
  (put [this id event]
    "Appends a new event to a cell's event log.")
  (load [this id]
    "Loads the most recent state snapshot for a given cell.")
  (get [this id]
    "Retrieves the sequence of all events appended for a cell."))
