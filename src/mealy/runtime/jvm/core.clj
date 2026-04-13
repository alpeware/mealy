(ns mealy.runtime.jvm.core
  "The JVM runtime entry point for Mealy cells. Adapts the pure Sans-IO core to core.async channels
  and wires it to the JVM EventStore and EventBus."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [hato.client :as hc]
            [mealy.action.core :as action]
            [mealy.cell.reducer :as reducer]
            [mealy.runtime.jvm.store :as store]
            [mealy.runtime.protocols :as p]))

(defmulti persist-event?
  "Determines whether an event should be persisted to the event log.
  Ephemeral events like `:tick` provide no historical value and are bypassed
  to prevent log bloat, as their impact (time progression) is captured in periodic state snapshots."
  first)

(defmethod persist-event? :default
  [_]
  true)

(defmethod persist-event? :tick
  [_]
  false)

(defn- execute-http-request
  "Intercepts and executes :http-request actions using hato asynchronously."
  [{:keys [req callback-event]} {:keys [cell-in-chan]}]
  (let [future (hc/request (assoc req :async? true))]
    (-> future
        (.thenAccept
         (reify java.util.function.Consumer
           (accept [_ resp]
             (let [status (:status resp)
                   body (:body resp)]
               (async/put! cell-in-chan [callback-event {:response {:status status :body body}}])))))
        (.exceptionally
         (reify java.util.function.Function
           (apply [_ ex]
             (let [cause (if (instance? java.util.concurrent.CompletionException ex)
                           (.getCause ex)
                           ex)]
               (if (instance? clojure.lang.ExceptionInfo cause)
                 (let [resp (ex-data cause)]
                   (async/put! cell-in-chan [callback-event {:error true :status (:status resp) :body (:body resp)}]))
                 (async/put! cell-in-chan [callback-event {:error true :reason (.getMessage cause)}])))
             nil))))))

(defn- start-worker-pool
  "Starts a generic worker pool to drain `out-chan` and execute actions via `mealy.action.core/execute`."
  [out-chan opts]
  (let [num-workers (:workers opts 4)
        env (assoc opts :out-chan out-chan)]
    (dotimes [_ num-workers]
      (go-loop []
        (when-let [cmd (<! out-chan)]
          (if (= (:type cmd) :http-request)
            (execute-http-request cmd env)
            (action/execute cmd env))
          (recur))))))

(defn start-node
  "Starts the JVM runtime node for a cell.
  Spawns a go-loop that consumes events from `in-chan`, processes them
  through the pure `reducer/handle-event`, persists them via `event-store`,
  and routes resulting commands to `out-chan` or `app-out-chan`.
  Registers the node on the `event-bus`.
  Returns a map containing the running channels and loop."
  ([event-store event-bus id initial-state in-chan out-chan]
   (start-node event-store event-bus id initial-state in-chan out-chan {}))
  ([event-store event-bus id initial-state in-chan out-chan opts]
   (let [snapshot-interval (:snapshot-interval opts)
         app-out-chan (:app-out-chan opts (async/chan 100))
         opts-with-app (assoc opts :app-out-chan app-out-chan)
         recovered-state (store/restore-cell event-store id initial-state)]

     (p/register event-bus id)
     (start-worker-pool out-chan opts-with-app)

     (let [node-loop (go-loop [state recovered-state
                               event-count (count (p/get event-store id))]
                       (if-let [event (<! in-chan)]
                         (let [_ (when (persist-event? event)
                                   (<! (async/thread (p/put event-store id event))))
                               {:keys [state actions]} (reducer/handle-event state event)
                               new-count (inc event-count)]

                           (when (and snapshot-interval
                                      (zero? (mod new-count snapshot-interval)))
                             (<! (async/thread (p/snapshot event-store id {:state state :event-count new-count}))))

                           (doseq [cmd actions]
                             (if (= (:type cmd) :app-event)
                               (>! app-out-chan cmd)
                               (when cmd (>! out-chan cmd))))
                           (recur state new-count))
                         (do
                           (async/close! out-chan)
                           (async/close! app-out-chan))))]
       {:in-chan in-chan
        :out-chan out-chan
        :app-out-chan app-out-chan
        :node-loop node-loop}))))
