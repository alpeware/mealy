(ns mealy.intelligence.llm
  "Pure helper functions for LLM orchestration.
   Extracts provider routing, response parsing, and consent evaluation
   from the reducer so they can be reused across different contexts."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [mealy.intelligence.adapters.gemini :as gemini]
            [mealy.intelligence.adapters.llama :as llama]
            [mealy.intelligence.core :as intel]))

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

(defn extract-edn-array
  "Helper to safely extract an EDN array from an LLM response."
  [s]
  (try
    (let [m (re-find #"(?s)\[.*\]" (or s ""))]
      (if m
        (let [parsed (edn/read-string m)]
          (if (vector? parsed) parsed []))
        []))
    (catch Exception _ [])))
