;; ==========================================================================
;; Mealy Cell — OODA Cognitive Bootstrap
;; ==========================================================================
;;
;; This file is the canonical cognitive pipeline for a Mealy Cell.
;; It is eval'd into the Cell's SCI sandbox at boot time, defining
;; all OODA-loop event handlers on the `handle-event` multimethod.
;;
;; The Cell can modify its own bootstrap by defining new methods or
;; overriding existing ones (except frozen kernel dispatch values).
;;
;; Dependencies (injected into SCI context by mealy.cell.boot):
;;   - mealy.cell.reducer/handle-event  (multimethod to extend)
;;   - mealy.intelligence.llm/*         (LLM orchestration)
;;   - mealy.ooda.prompt/*              (prompt compilation)
;;   - mealy.cell.mitosis/*             (complexity + division)
;;   - clojure.string/*                 (string manipulation)
;; ==========================================================================

(require '[mealy.cell.reducer :as reducer])
(require '[mealy.intelligence.llm :as llm])
(require '[mealy.ooda.prompt :as prompt])
(require '[clojure.string :as str])
(require '[mealy.cell.mitosis :as mitosis])

;; ---------------------------------------------------------------------------
;; :orient — OODA Orient: evaluate accumulated observations
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :orient
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
        (llm/route-llm-request (assoc state :phase :evaluating)
                               [{:role "system" :content prompt/agentic-system-prompt}
                                {:role "user" :content (prompt/compile-agentic-prompt state)}]
                               :high 1000 :orient-evaluated)))
    {:state state
     :actions []}))

;; ---------------------------------------------------------------------------
;; :orient-evaluated — OODA Generate Actions: process LLM agentic response
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :orient-evaluated
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)]
    (if (:error parsed)
      (or (llm/reroute-on-failure state-updated)
          {:state (-> state-updated (assoc :phase :idle) (assoc :last-error (:reason parsed)))
           :actions []})
      (let [actions-edn (llm/extract-edn-array (:response parsed))]
        {:state (-> state-updated
                    (assoc :phase :idle)
                    (update :memory dissoc :llm-routing-context))
         :actions actions-edn}))))

;; ---------------------------------------------------------------------------
;; :consent-evaluated — Original consent tracking
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :consent-evaluated
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)
        {:keys [consent]} (llm/parse-consent (:response parsed))]
    (if consent
      {:state (assoc state-updated :phase :acting)
       :actions []}
      {:state (assoc state-updated :phase :idle)
       :actions []})))

;; ---------------------------------------------------------------------------
;; :proposal — General proposal evaluation
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :proposal
  [state [_ event-data]]
  (let [prompt-txt (:prompt event-data)
        new-state (-> state
                      (update-in [:memory :pending-proposals] (fnil conj []) prompt-txt)
                      (assoc-in [:memory :eval-retries] 0))]
    (llm/route-llm-request (assoc new-state :phase :evaluating)
                           [{:role "system" :content prompt/sociocratic-system-prompt}
                            {:role "user" :content (prompt/compile-prompt new-state)}]
                           :high 1000 :proposal-evaluated)))

(defmethod reducer/handle-event :proposal-evaluated
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)]
    (if (:error parsed)
      (or (llm/reroute-on-failure state-updated)
          {:state (-> state-updated (assoc :phase :idle) (assoc :last-error (:reason parsed)))
           :actions []})
      (let [{:keys [consent]} (llm/parse-consent (:response parsed))
            proposals (get-in state-updated [:memory :pending-proposals] [])
            prompt-txt (first proposals)
            rem-proposals (vec (rest proposals))
            new-state (-> state-updated
                          (assoc-in [:memory :pending-proposals] rem-proposals)
                          (update :memory dissoc :llm-routing-context))]
        (if consent
          (llm/route-llm-request (assoc new-state :phase :generating-code)
                                 [{:role "system" :content prompt/code-gen-system-prompt}
                                  {:role "user" :content prompt-txt}]
                                 :high 2000 :code-generated)
          {:state (assoc new-state :phase :idle)
           :actions []})))))

(defmethod reducer/handle-event :code-generated
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)]
    (if (:error parsed)
      (or (llm/reroute-on-failure state-updated)
          {:state (-> state-updated (assoc :phase :idle) (assoc :last-error (:reason parsed)))
           :actions []})
      (let [;; Strip markdown code blocks if any
            code (-> (or (:response parsed) "")
                     (str/replace #"(?s)^```[a-z]*\n" "")
                     (str/replace #"(?s)\n```$" ""))
            new-state (-> state-updated
                          (assoc :phase :dry-running)
                          (assoc-in [:memory :pending-code] code)
                          (update :memory dissoc :llm-routing-context))]
        {:state new-state
         :actions [{:type :dry-run-eval :code code}]}))))

(defmethod reducer/handle-event :dry-run-success
  [state [_ event-data]]
  (let [code (:code event-data)
        new-state (assoc state :phase :code-review)]
    (llm/route-llm-request new-state
                           [{:role "system" :content prompt/code-review-system-prompt}
                            {:role "user" :content (str "Please review the following code:\n\n" code)}]
                           :high 2000 :code-review-evaluated)))

(defmethod reducer/handle-event :code-review-evaluated
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)
        response-str (or (:response parsed) "")
        consent? (and (str/includes? (str/upper-case response-str) "CONSENT")
                      (not (str/includes? (str/upper-case response-str) "OBJECTION")))
        code (get-in state-updated [:memory :pending-code])]
    (if consent?
      (let [new-state (-> state-updated
                          (assoc :phase :acting)
                          (update :memory dissoc :pending-code :eval-retries))]
        {:state new-state
         :actions [{:type :eval :code code}]})
      (let [new-state (-> state-updated
                          (assoc :phase :generating-code)
                          (update :memory dissoc :pending-code))]
        (reducer/handle-event new-state [:evaluation-error {:reason response-str :code code}])))))

;; ---------------------------------------------------------------------------
;; :propose-policy — Legacy compat: redirects to :proposal
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :propose-policy
  [state [_ event-data]]
  (reducer/handle-event state [:proposal event-data]))

(defmethod reducer/handle-event :policy-consent-evaluated
  [state [_ event-data]]
  (reducer/handle-event state [:proposal-evaluated event-data]))

;; ---------------------------------------------------------------------------
;; :policy-change — Administrative policy changes requiring Sociocratic consent
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :policy-change
  [state [_ event-data]]
  (let [new-policy (:policy event-data)
        new-state (-> state
                      (assoc-in [:memory :pending-policy-change] new-policy)
                      (assoc :phase :evaluating-policy))]
    (llm/route-llm-request new-state
                           [{:role "system" :content prompt/sociocratic-system-prompt}
                            {:role "user" :content (prompt/compile-policy-evaluation-prompt new-state new-policy)}]
                           :high 1000 :policy-self-evaluated)))

(defmethod reducer/handle-event :policy-self-evaluated
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)]
    (if (:error parsed)
      (or (llm/reroute-on-failure state-updated)
          {:state (-> state-updated
                      (update :memory dissoc :pending-policy-change)
                      (assoc :phase :idle)
                      (assoc :last-error (:reason parsed)))
           :actions []})
      (let [{:keys [consent]} (llm/parse-consent (:response parsed))
            pending-policy (get-in state-updated [:memory :pending-policy-change])
            state-clean (update state-updated :memory dissoc :llm-routing-context)]
        (if consent
          (let [parent (:parent state-clean)
                new-state (assoc state-clean :phase :awaiting-consent)]
            (if (= parent :anchor)
              {:state new-state
               :actions [{:type :app-event
                          :event-type :consent-request
                          :policy pending-policy}]}
              {:state new-state
               :actions [{:type :bus-publish
                          :topic parent
                          :event {:type :consent-request
                                  :from (:id state-clean)
                                  :policy pending-policy}}]}))
          {:state (-> state-clean
                      (update :memory dissoc :pending-policy-change)
                      (assoc :phase :idle))
           :actions []})))))

(defmethod reducer/handle-event :consent-granted
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

(defmethod reducer/handle-event :consent-rejected
  [state [_ _event-data]]
  {:state (-> state
              (update :memory dissoc :pending-policy-change)
              (assoc :phase :idle))
   :actions []})

;; ---------------------------------------------------------------------------
;; :think-request / :thought-result
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :think-request
  [state [_ event-data]]
  (let [complexity (or (:complexity event-data) :medium)]
    (llm/route-llm-request state
                           [{:role "system" :content (prompt/compile-think-system-prompt state)}
                            {:role "user" :content (:prompt event-data)}]
                           complexity 500 :thought-result)))

(defmethod reducer/handle-event :thought-result
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)]
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
;; :tap / :tap-response — Human chat
;; ---------------------------------------------------------------------------

(defmethod reducer/handle-event :tap
  [state [_ event-data]]
  (let [prompt (:prompt event-data)
        chat (get-in state [:memory :chat] [])
        updated-chat (conj chat {:role "user" :content prompt})
        new-state (assoc-in state [:memory :chat] updated-chat)
        system-prompt (prompt/compile-tap-system-prompt new-state)
        messages (into [{:role "system" :content system-prompt}]
                       updated-chat)]
    (llm/route-llm-request new-state messages :high 1000 :tap-response)))

(defmethod reducer/handle-event :tap-response
  [state [_ event-data]]
  (let [parsed (llm/parse-llm-response state (:response event-data))
        state-updated (llm/update-provider-state state parsed)]
    (if (:error parsed)
      {:state (-> state-updated
                  (assoc :phase :idle)
                  (assoc :last-error (:reason parsed)))
       :actions []}
      (let [reply (:response parsed)
            tools-edn (llm/extract-edn-array reply)
            new-state (-> state-updated
                          (assoc :phase :idle)
                          (update-in [:memory :chat] conj
                                     {:role "assistant" :content reply}))
            app-action {:type :app-event
                        :event-type :tap-response
                        :content reply}]
        {:state new-state
         :actions (into [app-action] tools-edn)}))))

;; ---------------------------------------------------------------------------
;; :heartbeat — Time-based trigger (extensible, fired by frozen :tick)
;; ---------------------------------------------------------------------------
;; The Cell can override this handler to add periodic tasks such as
;; feed checks, health monitoring, or scheduled proposals.

(defmethod reducer/handle-event :heartbeat
  [state [_ _event-data]]
  {:state state
   :actions []})
