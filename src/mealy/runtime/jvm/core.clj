(ns mealy.runtime.jvm.core
  "The JVM runtime entry point for Mealy cells. Adapts the pure Sans-IO core to core.async channels
  and wires it to the JVM EventStore and EventBus."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [hato.client :as hc]
            [mealy.action.core :as action]
            [mealy.cell.boot :as boot]
            [mealy.cell.core :as cell]
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

(.addMethod action/execute :http-request
            (with-meta
              (fn [{:keys [req callback-event]} env]
                (let [cell-in-chan (or (:cell-in-chan env) (:in-chan env))
                      future (hc/request (assoc req :async? true))]
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
              {:doc "Intercepts and executes :http-request actions using hato asynchronously."}))

(.addMethod action/execute :bus-publish
            (with-meta
              (fn [{:keys [topic event]} {:keys [event-bus]}]
                (when event-bus
                  (p/publish event-bus topic event)))
              {:doc "Publishes an event to a topic on the EventBus for inter-cell consent requests."}))

;; ---------------------------------------------------------------------------
;; Subscription (Source) action handlers — IO boundary concerns
;; ---------------------------------------------------------------------------

(defonce ^:private subscription-registry (atom {}))

(.addMethod action/execute :start-subscription
            (with-meta
              (fn [{:keys [config]} {:keys [cell-in-chan]}]
                (let [raw-handle ((requiring-resolve 'mealy.subscription.core/start-subscription) config {:cell-in-chan cell-in-chan})
                      handle-id (keyword (gensym "sub-"))]
                  (swap! subscription-registry assoc handle-id raw-handle)
                  (async/put! cell-in-chan [:observation {:type :subscription-started :config config :handle handle-id}])))
              {:doc "Starts a subscription that feeds events into the cell. Expects a :config map (e.g. {:type :tick :interval-ms 1000})."}))

(.addMethod action/execute :stop-subscription
            (with-meta
              (fn [{:keys [config handle]} _env]
                (if-let [raw-handle (get @subscription-registry handle)]
                  (do
                    ((requiring-resolve 'mealy.subscription.core/stop-subscription) config raw-handle)
                    (swap! subscription-registry dissoc handle))
                  (println "Warning: cannot stop subscription, handle not found:" handle)))
              {:doc "Stops a running subscription. Expects a :config map and the :handle keyword."}))

;; ---------------------------------------------------------------------------
;; :spawn-cell — LLM-driven mitosis
;; ---------------------------------------------------------------------------

(.addMethod action/execute :spawn-cell
            (with-meta
              (fn [{:keys [child-aim partition-keys bootstrap-mode]} {:keys [cell-in-chan]}]
                (let [mode (or bootstrap-mode :fresh)]
                  (async/put! cell-in-chan
                              [:observation {:type :spawn-request
                                             :child-aim child-aim
                                             :partition-keys (set (or partition-keys []))
                                             :bootstrap-mode mode}])))
              {:doc "Requests Cell mitosis. The runtime creates a new child node with the given aim. :bootstrap-mode can be :fresh (canonical bootstrap) or :inherit (parent's bootstrap)."}))

(defn- start-worker-pool
  "Starts a generic worker pool to drain `out-chan` and execute actions via `mealy.action.core/execute`."
  [out-chan opts]
  (let [num-workers (:workers opts 4)
        env (assoc opts :out-chan out-chan)]
    (dotimes [_ num-workers]
      (go-loop []
        (when-let [cmd (<! out-chan)]
          (try
            (action/execute cmd env)
            (catch Exception e
              (println "Worker execution failed:" (.getMessage e))))
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
         ;; Pass the cell's SCI context and the event-bus into the worker env
         cell-sci-ctx (:sci-ctx initial-state)
         opts-with-extras (assoc opts
                                 :app-out-chan app-out-chan
                                 :cell-sci-ctx cell-sci-ctx
                                 :event-bus event-bus)
         recovered-state (store/restore-cell event-store id initial-state)]

     ;; Boot the Cell's SCI context: register bootstrap namespaces + eval bootstrap.clj
     (boot/boot-cell! (:sci-ctx recovered-state))

     (p/register event-bus id)
     (start-worker-pool out-chan opts-with-extras)

     (let [node-loop (go-loop [state recovered-state
                               event-count (count (p/get event-store id))]
                       (if-let [event (<! in-chan)]
                         (let [_ (when (persist-event? event)
                                   (<! (async/thread (p/put event-store id event))))
                               {:keys [state actions]} (reducer/handle-event state event)
                               new-count (inc event-count)]

                           (when-let [sa (:state-atom opts)]
                             (reset! sa state))

                           (when (and snapshot-interval
                                      (zero? (mod new-count snapshot-interval)))
                             (<! (async/thread
                                   (p/snapshot event-store id
                                               {:state (cell/sanitize-for-snapshot state)
                                                :event-count new-count}))))

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
