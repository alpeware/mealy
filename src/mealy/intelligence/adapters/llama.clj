(ns mealy.intelligence.adapters.llama
  "Llama Adapter for Mealy Intelligence.
   Implements a non-blocking Provider Actor that interfaces with a local Llama instance
   (e.g., via the local Ollama REST API)."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [hato.client :as hc]
            [mealy.intelligence.provider :as provider]))

(def ^:private sociocratic-system-prompt "You are the deterministic cognitive routing engine for an autonomous agent. Your ONLY purpose is to evaluate the user's provided State and Proposed Policy. You do not converse. You do not offer help. If the Proposed Policy does not critically harm the Aim or violate Memory, you MUST output exactly: 'CONSENT: [brief reason]'. If it violates a critical constraint, output exactly: 'OBJECTION: [reason]'.")

(defn build-request
  "Pure function to build the hato request map."
  [url _model prompt]
  {:url (str url "/v1/chat/completions")
   :method :post
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:messages [{:role "system" :content sociocratic-system-prompt}
                      {:role "user" :content prompt}]
           :max_tokens 2048
           :stop ["<|im_end|>" "<|endoftext|>"]
           :stream false})})

(defn parse-response
  "Pure function to parse the HTTP response map from Ollama."
  [{:keys [status body]}]
  (let [parsed-body (try
                      (json/parse-string body true)
                      (catch Exception _
                        body))]
    (if (and (>= status 200) (< status 300))
      (let [response-text (-> parsed-body :choices first :message :content)
            token-count (-> parsed-body :usage :total_tokens)]
        {:response response-text
         :tokens (or token-count 0)})
      (let [error-msg (if (map? parsed-body)
                        (or (:error parsed-body) (str "HTTP Error: " status))
                        (str "HTTP Error: " status))]
        {:error true
         :reason error-msg
         :backoff-ms 1000}))))

(defn parse-exception
  "Pure function to handle network/connection exceptions."
  [ex]
  {:error true
   :reason (.getMessage ex)
   :backoff-ms 5000})

(defn create-llm-fn
  "Creates an llm-fn that sends non-blocking async requests to the Ollama API.
   Returns a function `(fn [prompt]) -> channel` that fulfills the provider contract."
  [url model]
  (fn [prompt]
    (let [res-chan (async/chan 1)
          req (build-request url model prompt)
          future (hc/request (assoc req :async? true))]
      (-> future
          (.thenAccept
           (reify java.util.function.Consumer
             (accept [_ resp]
               (async/put! res-chan (parse-response resp)))))
          (.exceptionally
           (reify java.util.function.Function
             (apply [_ ex]
               (let [cause (if (instance? java.util.concurrent.CompletionException ex)
                             (.getCause ex)
                             ex)]
                 (if (instance? clojure.lang.ExceptionInfo cause)
                   ;; This means hato threw an ExceptionInfo, likely containing the raw response data
                   (let [resp (ex-data cause)]
                     (async/put! res-chan (parse-response resp)))
                   ;; True network error
                   (async/put! res-chan (parse-exception cause))))
               nil))))
      res-chan)))

(defn start-llama-provider
  "Spawns a go-loop that wraps the Llama adapter inside a Provider actor."
  [config url model cmd-chan]
  (provider/start-provider config cmd-chan (create-llm-fn url model)))
