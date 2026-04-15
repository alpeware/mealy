(ns mealy.subscription.core
  "The Subscription Foundation. Provides an extensible mechanism for
  pluggable event sources that push observations into a cell's input channel.
  Subscriptions are started and stopped via multimethods dispatching on type."
  (:require [clojure.core.async :as async]))

(defmulti start-subscription
  "Starts a subscription of the given type. Receives a subscription config map
  and an env map containing at minimum :cell-in-chan.
  Returns an opaque handle that can be passed to `stop-subscription`."
  (fn [config _env]
    (:type config)))

(defmulti stop-subscription
  "Stops a running subscription. Receives the subscription config map
  and the opaque handle returned by `start-subscription`."
  (fn [config _handle]
    (:type config)))

;; ---------------------------------------------------------------------------
;; Built-in: :tick — periodic heartbeat
;; ---------------------------------------------------------------------------

(defmethod start-subscription :tick
  [{:keys [interval-ms] :or {interval-ms 1000}} {:keys [cell-in-chan]}]
  (let [running (atom true)]
    (async/go-loop []
      (async/<! (async/timeout interval-ms))
      (when @running
        (when (async/put! cell-in-chan [:tick {:timestamp (System/currentTimeMillis)}])
          (recur))))
    running))

(defmethod stop-subscription :tick
  [_config handle]
  (reset! handle false))
