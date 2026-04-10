(ns mealy.runtime.jvm.bus
  "JVM implementation of the EventBus protocol using clojure.core.async."
  (:require [clojure.core.async :as async]
            [mealy.runtime.protocols :as p]))

(defrecord JVMEventBus [bus-chan publisher subscription-info registered]
  p/EventBus
  (register [_ id]
    (swap! registered conj id))
  (subscribe [_ id topic handler]
    (let [ch (async/chan 10)]
      (async/go-loop []
        (when-let [event (async/<! ch)]
          (handler event)
          (recur)))
      (async/sub publisher topic ch)
      (swap! subscription-info update-in [topic id] (constantly ch))))
  (publish [_ topic event]
    (async/put! bus-chan {:topic topic :event event}))
  (query [_ topic]
    (get @subscription-info topic {})))

(defn make-bus
  "Creates a JVMEventBus with its background publisher go block running."
  []
  (let [bus-chan (async/chan 100)
        publisher (async/pub bus-chan :topic)]
    (->JVMEventBus bus-chan publisher (atom {}) (atom #{}))))
