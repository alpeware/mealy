(ns mealy.cell.reducer
  "The pure Sans-IO Mealy machine reducer."
  (:require [clojure.string :as str]
            [mealy.intelligence.adapters.gemini :as gemini]
            [mealy.intelligence.adapters.llama :as llama]
            [mealy.intelligence.core :as intel]
            [mealy.ooda.prompt :as prompt]))

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
            ;; We only have gemini and llama, we'll route based on adapter-type in the provider map
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

(defn handle-observation
  "Handles an :observation event, optionally triggering a reflex or transitioning to :evaluating."
  [state [_ event-data]]
  (let [state-with-obs (update state :observations conj event-data)
        new-state (if (and (= (:type event-data) :eval-success)
                           (:code event-data))
                    (update-in state-with-obs [:memory :active-policies] (fnil conj []) (:code event-data))
                    state-with-obs)]
    (if (= (:phase new-state) :idle)
      (let [reflexes (get-in state [:memory :reflexes])
            reflex-match (or (get reflexes (:type event-data))
                             (get reflexes event-data))]
        (if reflex-match
          {:state new-state
           :actions [reflex-match]}
          (route-llm-request (assoc new-state :phase :evaluating)
                             [{:role "system" :content prompt/sociocratic-system-prompt}
                              {:role "user" :content (prompt/compile-prompt new-state)}]
                             :high 1000 :consent-evaluated)))
      {:state new-state
       :actions []})))

(defn handle-consent-evaluated
  "Handles the LLM response to a consent evaluation."
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)
        {:keys [consent]} (parse-consent (:response parsed))]
    (if consent
      {:state (assoc state-updated :phase :acting)
       :actions []}
      {:state (assoc state-updated :phase :idle)
       :actions []})))

(defn handle-propose-policy
  "Handles a request to propose a new policy, transitioning to :evaluating to seek consent."
  [state [_ event-data]]
  (let [code (:code event-data)
        new-state (update-in state [:memory :proposed-policies] (fnil conj []) code)]
    (route-llm-request (assoc new-state :phase :evaluating)
                       [{:role "system" :content prompt/sociocratic-system-prompt}
                        {:role "user" :content (prompt/compile-prompt new-state)}]
                       :high 1000 :policy-consent-evaluated)))

(defn handle-policy-consent-evaluated
  "Handles the LLM response to a policy proposal consent evaluation."
  [state [_ event-data]]
  (let [parsed (parse-llm-response state (:response event-data))
        state-updated (update-provider-state state parsed)
        {:keys [consent]} (parse-consent (:response parsed))
        policies (get-in state-updated [:memory :proposed-policies] [])
        policy (first policies)
        rem-policies (vec (rest policies))
        new-state (assoc-in state-updated [:memory :proposed-policies] rem-policies)]
    (if consent
      {:state (assoc new-state :phase :acting)
       :actions [{:type :eval
                  :code policy}]}
      {:state (assoc new-state :phase :idle)
       :actions []})))

(defn handle-think-request
  "Handles a request to think, routing it to an LLM provider."
  [state [_ event-data]]
  (route-llm-request state
                     [{:role "user" :content (:prompt event-data)}]
                     :low 500 :thought-result))

(defn handle-evaluation-error
  "Handles an error during evaluation, transitioning to :idle."
  [state [_ event-data]]
  {:state (-> state
              (assoc :phase :idle)
              (assoc :last-error (:reason event-data)))
   :actions []})

(def default-handlers
  "The built-in set of pure handlers for standard mealy actions."
  {:observation handle-observation
   :consent-evaluated handle-consent-evaluated
   :propose-policy handle-propose-policy
   :policy-consent-evaluated handle-policy-consent-evaluated
   :think-request handle-think-request
   :evaluation-error handle-evaluation-error})

(defn handle-event
  "Pure evaluation loop that routes incoming events to the appropriate handler function
  defined within the Cell's `:handlers` state registry, falling back to `default-handlers`.
  Returns a map containing the updated `:state` and a vector of `:actions`."
  [state [event-type _ :as event]]
  (let [handler (or (get (:handlers state) event-type)
                    (get default-handlers event-type))]
    (if handler
      (handler state event)
      {:state state :actions []})))
