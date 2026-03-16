(ns mealy.intelligence.adapters.gemini
  "Gemini Adapter for Mealy Intelligence.
   Implements a non-blocking Provider Actor that interfaces securely with the Gemini REST API."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [hato.client :as hc]
            [mealy.intelligence.provider :as provider]))

(defn build-request
  "Pure function to build the hato request map."
  [api-key model prompt]
  {:url (str "https://generativelanguage.googleapis.com/v1beta/models/" model ":generateContent?key=" api-key)
   :method :post
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string
          {:contents [{:parts [{:text prompt}]}]})})

(defn parse-response
  "Pure function to parse the HTTP response map from Gemini."
  [{:keys [status body]}]
  (let [parsed-body (try
                      (json/parse-string body true)
                      (catch Exception _
                        body))]
    (if (and (>= status 200) (< status 300))
      (let [response-text (-> parsed-body
                              :candidates
                              first
                              :content
                              :parts
                              first
                              :text)
            token-count (-> parsed-body
                            :usageMetadata
                            :totalTokenCount)]
        {:response response-text
         :tokens (or token-count 0)})
      (let [error-msg (or (-> parsed-body :error :message) (str "HTTP Error: " status))]
        {:error true
         :reason error-msg
         ;; Give a 60s backoff for 429s (rate limit), 1s backoff for others.
         :backoff-ms (if (= status 429) 60000 1000)}))))

(defn parse-exception
  "Pure function to handle network/connection exceptions."
  [ex]
  {:error true
   :reason (.getMessage ex)
   :backoff-ms 5000})

(defn create-llm-fn
  "Creates an llm-fn that sends non-blocking async requests to the Gemini API.
   Returns a function `(fn [prompt]) -> channel` that fulfills the provider contract."
  [api-key model]
  (fn [prompt]
    (let [res-chan (async/chan 1)
          req (build-request api-key model prompt)
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

(defn start-gemini-provider
  "Spawns a go-loop that wraps the Gemini adapter inside a Provider actor."
  [config api-key model cmd-chan]
  (provider/start-provider config cmd-chan (create-llm-fn api-key model)))
