(ns mealy.intelligence.adapters.llama
  "Llama Adapter for Mealy Intelligence.
   Implements a non-blocking Provider Actor that interfaces with a local Llama instance
   (e.g., via the local Ollama REST API)."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [hato.client :as hc]
            [mealy.intelligence.provider :as provider]))

(defn build-request
  "Pure function to build the hato request map."
  [url _model messages]
  {:url (str url "/v1/chat/completions")
   :method :post
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:messages messages
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
   Returns a function `(fn [messages]) -> channel` that fulfills the provider contract."
  [url model]
  (fn [messages]
    (let [res-chan (async/chan 1)
          req (build-request url model messages)
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
