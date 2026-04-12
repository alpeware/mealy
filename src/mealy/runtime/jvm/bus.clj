(ns mealy.runtime.jvm.bus
  "JVM implementation of the EventBus protocol using clojure.core.async."
  (:require [clojure.core.async :as async]
            [mealy.runtime.protocols :as p]))

(defrecord JVMEventBus [bus-chan publisher subscription-info registered]
  p/EventBus
  (register [_ topic]
    (swap! registered conj topic))
  (subscribe [_ id topic handler]
    (let [ch (async/chan 10)]
      (async/go-loop []
        (when-let [event (async/<! ch)]
          (handler event)
          (recur)))
      (async/sub publisher topic ch)
      (swap! subscription-info assoc-in [topic id] ch)))
  (unsubscribe [_ topic id]
    (when-let [ch (get-in @subscription-info [topic id])]
      (async/unsub publisher topic ch)
      (async/close! ch)
      (swap! subscription-info update topic dissoc id)))
  (publish [_ topic event]
    (async/put! bus-chan {:topic topic :event event}))
  (query [_]
    @registered)
  (query [_ topic]
    (get @subscription-info topic {})))

(defn make-bus
  "Creates a JVMEventBus with its background publisher go block running."
  []
  (let [bus-chan (async/chan 100)
        publisher (async/pub bus-chan :topic)]
    (->JVMEventBus bus-chan publisher (atom {}) (atom #{}))))
