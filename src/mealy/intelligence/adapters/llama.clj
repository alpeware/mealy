(ns mealy.intelligence.adapters.llama
  "Llama Adapter for Mealy Intelligence.
   Implements a non-blocking Provider Actor that interfaces with a local Llama instance
   (e.g., via the local Ollama REST API)."
  (:require [cheshire.core :as json]))

(defn build-request
  "Pure function to build the hato request map."
  [url _model messages]
  {:url (str url "/v1/chat/completions")
   :method :post
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:messages messages
           :max_tokens 262144
           :stop ["<|im_end|>" "<|endoftext|>" "<|end_of_turn|>"]
           :stream false})})

(defn parse-response
  "Pure function to parse the HTTP response map from Ollama."
  [{:keys [body] :as response-map}]
  (let [status (or (:status response-map) 500)
        parsed-body (try
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

