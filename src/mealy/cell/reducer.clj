(ns mealy.cell.reducer
  "The Frozen Kernel of the Mealy machine reducer.
  Contains only the immutable safety-critical event handlers that MUST NOT
  be overridden by the Cell's self-modification via SCI bootstrap.

  The OODA cognitive pipeline (orient, proposal, policy-change, tap, etc.)
  lives in resources/bootstrap.clj and is eval'd into the Cell's SCI context
  at boot time by mealy.cell.boot/boot-cell!.

  Frozen handlers:
    :default          — Safety net for unhandled events
    :observation      — Core invariant: accumulate observations
    :evaluation-error — Self-healing error recovery (non-overridable)
    :tick             — Source-fed heartbeat (requires Java interop)"
  (:require [mealy.intelligence.llm :as llm]
            [mealy.ooda.prompt :as prompt]))

;; ---------------------------------------------------------------------------
;; Frozen dispatch values — these cannot be overridden by bootstrap scripts
;; ---------------------------------------------------------------------------

(def frozen-dispatch-values
  "Set of event-type keywords whose handlers are part of the frozen kernel
  and MUST NOT be replaced by SCI-evaluated bootstrap code."
  #{:default :observation :evaluation-error :tick})

;; ---------------------------------------------------------------------------
;; Extensible event handler multimethod
;; ---------------------------------------------------------------------------

(defmulti handle-event
  "Pure evaluation loop that routes incoming events to the appropriate handler.
  Dispatches on the event-type keyword (first element of the event vector).
  Each method receives the full state map and the event vector,
  and must return {:state <new-state> :actions [<action-maps>]}."
  (fn [_state [event-type _ :as _event]]
    event-type))

(defmethod handle-event :default
  [state _event]
  {:state state :actions []})

(defn- make-guarded-handle-event
  "Creates a proxy MultiFn that delegates to the real handle-event but
  rejects addMethod calls on frozen dispatch values. This ensures that
  SCI bootstrap code can extend handle-event with new event types but
  cannot overwrite safety-critical frozen kernel handlers."
  []
  (let [real-mf handle-event]
    (proxy [clojure.lang.MultiFn] ["handle-event"
                                   (fn [_state [event-type _]] event-type)
                                   :default
                                   (atom (make-hierarchy))]
      ;; Dispatch to the real multimethod
      (invoke [state event]
        (.invoke real-mf state event))
      ;; Guard: reject writes to frozen dispatch values
      (addMethod [dispatch-val method]
        (if (contains? frozen-dispatch-values dispatch-val)
          (throw (ex-info (str "Cannot override frozen kernel handler: " dispatch-val)
                          {:dispatch-val dispatch-val :frozen frozen-dispatch-values}))
          (.addMethod real-mf dispatch-val method))
        this)
      ;; Delegate introspection to the real multimethod
      (getMethodTable []
        (.getMethodTable real-mf))
      (getMethod [dispatch-val]
        (.getMethod real-mf dispatch-val)))))

(defn register-reducer-ns!
  "Registers a guarded handle-event proxy into a given SCI context.
  The proxy delegates all calls to the real multimethod but rejects
  defmethod on frozen dispatch values (see frozen-dispatch-values).
  This enforces kernel integrity while allowing bootstrap extensions."
  [ctx]
  (swap! (:env ctx) assoc-in [:namespaces 'mealy.cell.reducer 'handle-event]
         (make-guarded-handle-event))
  ctx)

;; ---------------------------------------------------------------------------
;; :observation — OODA Observe: accumulate only (FROZEN)
;; ---------------------------------------------------------------------------

(defmethod handle-event :observation
  [state [_ event-data]]
  (let [new-state (update state :observations conj event-data)]
    {:state new-state
     :actions []}))

;; ---------------------------------------------------------------------------
;; :evaluation-error — Self-healing retry loop (FROZEN)
;; ---------------------------------------------------------------------------

(defmethod handle-event :evaluation-error
  [state [_ event-data]]
  (let [code (:code event-data)
        reason (:reason event-data)
        retries (get-in state [:memory :eval-retries] 0)]
    (if (< retries 3)
      (let [feedback-prompt (str "The following code failed to evaluate:\n" code "\n\nError: " reason "\n\nPlease rewrite it.")
            new-state (-> state
                          (assoc :phase :generating-code)
                          (assoc-in [:memory :eval-retries] (inc retries)))]
        (llm/route-llm-request new-state
                               [{:role "system" :content prompt/code-gen-system-prompt}
                                {:role "user" :content feedback-prompt}]
                               :high 2000 :code-generated))
      {:state (-> state
                  (assoc :phase :idle)
                  (assoc :last-error reason)
                  (assoc-in [:memory :eval-retries] 0)
                  (update-in [:memory :chat] conj {:role "assistant" :content (str "Code evaluation failed permanently after 3 retries:\n" reason)}))
       :actions []})))

;; ---------------------------------------------------------------------------
;; :tick — Heartbeat (FROZEN — requires Java interop not available in SCI)
;; ---------------------------------------------------------------------------

(defn- epoch-ms->local-str
  "Converts epoch milliseconds to a human-readable local timestamp string
   including the day of the week, e.g. 'Monday, 2026-04-14 10:55:24 CDT'."
  [epoch-ms]
  (let [instant (java.time.Instant/ofEpochMilli epoch-ms)
        zoned (.atZone instant (java.time.ZoneId/systemDefault))
        formatter (java.time.format.DateTimeFormatter/ofPattern "EEEE, yyyy-MM-dd HH:mm:ss z")]
    (.format zoned formatter)))

(defmethod handle-event :tick
  [state [_ event-data]]
  (let [ts (:timestamp event-data)
        human-time (epoch-ms->local-str ts)
        updated-state (-> state
                          (assoc-in [:memory :current-time] human-time)
                          (assoc-in [:memory :timestamp] ts))]
    {:state updated-state
     :actions [{:type :inject-event
                :event [:heartbeat {:timestamp ts :time human-time}]}]}))
