(ns mealy.cell.reducer
  "The pure Sans-IO Mealy machine reducer.
  Event handling is extensible via the `handle-event` defmulti, dispatching
  on the first element (event-type keyword) of each event vector."
  (:require [clojure.string :as str]
            [mealy.intelligence.adapters.gemini :as gemini]
            [mealy.intelligence.adapters.llama :as llama]
            [mealy.intelligence.core :as intel]
            [mealy.ooda.prompt :as prompt]))

;; ---------------------------------------------------------------------------
;; Shared pure helpers
;; ---------------------------------------------------------------------------

(defn route-llm-request
  "Pure function to select the best provider, construct an HTTP request action,
  and update the state with the selected provider."
  [state messages required-complexity estimated-tokens callback-event]
  (let [providers (get-in state [:memory :providers] {})
        best-provider-id (intel/select-best-provider providers required-complexity estimated-tokens)]
    (if best-provider-id
      (let [provider (get providers best-provider-id)
            url (:url provider)
            model (:model provider)
            api-key (:api-key provider)
            req (case (:adapter-type provider)
                  :gemini (gemini/build-request api-key model messages)
                  :llama (llama/build-request url model messages))
            new-state (assoc-in state [:memory :active-provider] best-provider-id)]
        {:state new-state
         :actions [{:type :http-request
                    :req req
                    :callback-event callback-event}]})
      {:state (-> state
                  (assoc :phase :idle)
                  (assoc :last-error "No available provider for request"))
       :actions []})))

(defn parse-llm-response
  "Pure function to route the response to the correct parser based on the active provider."
  [state response-data]
  (let [provider-id (get-in state [:memory :active-provider])
        provider (get-in state [:memory :providers provider-id])]
    (case (:adapter-type provider)
      :gemini (gemini/parse-response response-data)
      :llama (llama/parse-response response-data)
      ;; fallback to avoid nil errors, treat as generic text or error
      (if (:error response-data)
        {:error true :reason (:reason response-data) :backoff-ms 1000}
        {:response (-> response-data :body) :tokens 0}))))

(defn update-provider-state
  "Pure function to apply budget deductions or backoffs based on parsed response."
  [state parsed-response]
  (let [provider-id (get-in state [:memory :active-provider])
        provider (get-in state [:memory :providers provider-id])
        now-ms (System/currentTimeMillis)]
    (if provider-id
      (let [updated-provider (if (:error parsed-response)
                               (intel/set-backoff provider (or (:backoff-ms parsed-response) 1000) now-ms)
                               (intel/deduct-budget provider (:tokens parsed-response)))
            new-state (-> state
                          (assoc-in [:memory :providers provider-id] updated-provider)
                          (update :memory dissoc :active-provider))]
        new-state)
      state)))

(defn parse-consent
  "Pure function to parse the LLM's response string.
  Returns a map containing :consent (boolean) and the original :response."
  [response]
  (let [response-str (if response (str response) "")
        upper (str/upper-case response-str)
        consent? (and (str/includes? upper "CONSENT")
                      (not (str/includes? upper "OBJECTION")))]
    {:consent consent?
     :response response-str}))

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

;; ---------------------------------------------------------------------------
;; :observation — OODA Observe: accumulate only
;; ---------------------------------------------------------------------------

(defmethod handle-event :observation
  [state [_ event-data]]
  (let [new-state (update state :observations conj event-data)]
    {:state new-state
     :actions []}))

;; ---------------------------------------------------------------------------
;; :orient — OODA Orient: evaluate accumulated observations
;; ---------------------------------------------------------------------------

(defmethod handle-event :orient
  [state _event]
  (if (= (:phase state) :idle)
    (let [reflexes (get-in state [:memory :reflexes])
          last-obs (last (:observations state))
          reflex-match (when last-obs
                         (or (get reflexes (:type last-obs))
                             (get reflexes last-obs)))]
      (if reflex-match
        {:state state
         :actions [reflex-match]}
        (route-llm-request (assoc state :phase :evaluating)
                           [{:role "system" :content prompt/sociocratic-system-prompt}
                            {:role "user" :content (prompt/compile-prompt state)}]
                           :high 1000 :consent-evaluated)))
    {:state state
     :actions []}))

;; ---------------------------------------------------------------------------
;; :consent-evaluated — OODA Decide: process LLM consent response
;; ---------------------------------------------------------------------------

(defmethod handle-event :consent-evaluated
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)
        {:keys [consent]} (parse-consent (:response parsed))]
    (if consent
      {:state (assoc state-updated :phase :acting)
       :actions []}
      {:state (assoc state-updated :phase :idle)
       :actions []})))

;; ---------------------------------------------------------------------------
;; :proposal — General proposal evaluation
;; Operational decisions (code, new skills) are evaluated against Aim +
;; Policies by the cell autonomously.  If the cell consents, :eval executes.
;; ---------------------------------------------------------------------------

(defmethod handle-event :proposal
  [state [_ event-data]]
  (let [code (:code event-data)
        new-state (update-in state [:memory :pending-proposals] (fnil conj []) code)]
    (route-llm-request (assoc new-state :phase :evaluating)
                       [{:role "system" :content prompt/sociocratic-system-prompt}
                        {:role "user" :content (prompt/compile-prompt new-state)}]
                       :high 1000 :proposal-evaluated)))

(defmethod handle-event :proposal-evaluated
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)
        {:keys [consent]} (parse-consent (:response parsed))
        proposals (get-in state-updated [:memory :pending-proposals] [])
        proposal (first proposals)
        rem-proposals (vec (rest proposals))
        new-state (assoc-in state-updated [:memory :pending-proposals] rem-proposals)]
    (if consent
      {:state (assoc new-state :phase :acting)
       :actions [{:type :eval
                  :code proposal}]}
      {:state (assoc new-state :phase :idle)
       :actions []})))

;; ---------------------------------------------------------------------------
;; :propose-policy — Legacy compat: redirects to :proposal
;; ---------------------------------------------------------------------------

(defmethod handle-event :propose-policy
  [state [_ event-data]]
  (handle-event state [:proposal event-data]))

(defmethod handle-event :policy-consent-evaluated
  [state [_ event-data]]
  (handle-event state [:proposal-evaluated event-data]))

;; ---------------------------------------------------------------------------
;; :policy-change — Administrative policy changes requiring Sociocratic consent
;;
;; Two-phase consent round:
;;   Phase 1: The cell itself evaluates the proposed policy via LLM.
;;            → :policy-self-evaluated callback
;;   Phase 2: If the cell consents, the other representative is asked:
;;            - :anchor (human) → :app-event consent request
;;            - parent cell     → :bus-publish consent request
;;   Both parties must consent for the policy to be adopted.
;; ---------------------------------------------------------------------------

(defmethod handle-event :policy-change
  [state [_ event-data]]
  (let [new-policy (:policy event-data)
        new-state (-> state
                      (assoc-in [:memory :pending-policy-change] new-policy)
                      (assoc :phase :evaluating-policy))]
    ;; Phase 1: Cell self-evaluates the proposed policy
    (route-llm-request new-state
                       [{:role "system" :content prompt/sociocratic-system-prompt}
                        {:role "user" :content (prompt/compile-policy-evaluation-prompt new-state new-policy)}]
                       :high 1000 :policy-self-evaluated)))

(defmethod handle-event :policy-self-evaluated
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)
        {:keys [consent]} (parse-consent (:response parsed))
        pending-policy (get-in state-updated [:memory :pending-policy-change])]
    (if consent
      ;; Cell consents → Phase 2: ask the other representative
      (let [parent (:parent state-updated)
            new-state (assoc state-updated :phase :awaiting-consent)]
        (if (= parent :anchor)
          ;; Root cell: human is the other representative → emit app-event
          {:state new-state
           :actions [{:type :app-event
                      :event-type :consent-request
                      :policy pending-policy}]}
          ;; Child cell: parent cell is the other representative → emit bus event
          {:state new-state
           :actions [{:type :bus-publish
                      :topic parent
                      :event {:type :consent-request
                              :from (:id state-updated)
                              :policy pending-policy}}]}))
      ;; Cell itself objects → abort, return to idle
      {:state (-> state-updated
                  (update :memory dissoc :pending-policy-change)
                  (assoc :phase :idle))
       :actions []})))

(defmethod handle-event :consent-granted
  [state [_ event-data]]
  (let [policy (or (:policy event-data)
                   (get-in state [:memory :pending-policy-change]))]
    (if (and (= (:phase state) :awaiting-consent) policy)
      {:state (-> state
                  (update :policies conj policy)
                  (update :memory dissoc :pending-policy-change)
                  (assoc :phase :idle))
       :actions []}
      {:state state :actions []})))

(defmethod handle-event :consent-rejected
  [state [_ _event-data]]
  {:state (-> state
              (update :memory dissoc :pending-policy-change)
              (assoc :phase :idle))
   :actions []})

;; ---------------------------------------------------------------------------
;; :think-request / :thought-result
;; ---------------------------------------------------------------------------

(defmethod handle-event :think-request
  [state [_ event-data]]
  (route-llm-request state
                     [{:role "user" :content (:prompt event-data)}]
                     :low 500 :thought-result))

(defmethod handle-event :thought-result
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)]
    (if (:error parsed)
      {:state (-> state-updated
                  (assoc :phase :idle)
                  (assoc :last-error (:reason parsed)))
       :actions []}
      {:state (-> state-updated
                  (assoc :phase :idle)
                  (update :observations conj {:type :thought
                                              :content (:response parsed)
                                              :tokens (:tokens parsed)}))
       :actions []})))

;; ---------------------------------------------------------------------------
;; :tick — Heartbeat
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
        human-time (epoch-ms->local-str ts)]
    {:state (assoc-in state [:memory :current-time] human-time)
     :actions []}))

;; ---------------------------------------------------------------------------
;; :tap / :tap-response — Human chat
;; ---------------------------------------------------------------------------

(defmethod handle-event :tap
  [state [_ event-data]]
  (let [prompt (:prompt event-data)
        chat (get-in state [:memory :chat] [])
        updated-chat (conj chat {:role "user" :content prompt})
        new-state (assoc-in state [:memory :chat] updated-chat)
        system-prompt (prompt/compile-tap-system-prompt new-state)
        messages (into [{:role "system" :content system-prompt}]
                       updated-chat)]
    (route-llm-request new-state messages :low 1000 :tap-response)))

(defmethod handle-event :tap-response
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)]
    (if (:error parsed)
      {:state (-> state-updated
                  (assoc :phase :idle)
                  (assoc :last-error (:reason parsed)))
       :actions []}
      (let [reply (:response parsed)
            new-state (-> state-updated
                          (assoc :phase :idle)
                          (update-in [:memory :chat] conj
                                     {:role "assistant" :content reply}))]
        {:state new-state
         :actions [{:type :app-event
                    :event-type :tap-response
                    :content reply}]}))))

;; ---------------------------------------------------------------------------
;; :evaluation-error
;; ---------------------------------------------------------------------------

(defmethod handle-event :evaluation-error
  [state [_ event-data]]
  {:state (-> state
              (assoc :phase :idle)
              (assoc :last-error (:reason event-data)))
   :actions []})
