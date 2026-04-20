(ns mealy.intelligence.adapters.gemini
  "Gemini Adapter for Mealy Intelligence.
   Implements a non-blocking Provider Actor that interfaces securely with the Gemini REST API."
  (:require [cheshire.core :as json]))

(defn build-request
  "Pure function to build the hato request map."
  [api-key model messages]
  (let [system-message (first (filter #(= (:role %) "system") messages))
        other-messages (filter #(not= (:role %) "system") messages)
        contents (mapv (fn [m] {:parts [{:text (:content m)}]}) other-messages)
        body-map (cond-> {:contents contents}
                   system-message (assoc :systemInstruction {:parts [{:text (:content system-message)}]}))]
    {:url (str "https://generativelanguage.googleapis.com/v1beta/models/" model ":generateContent?key=" api-key)
     :method :post
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string body-map)}))

(defn parse-response
  "Pure function to parse the HTTP response map from Gemini.
   Expects :body to be an already-parsed EDN map (auto-parsed by the
   :http-request executor). Falls back to JSON parsing if body is a string."
  [response-map]
  (let [status (or (:status response-map) 500)
        body (:body response-map)
        parsed-body (if (string? body)
                      (try (json/parse-string body true) (catch Exception _ body))
                      body)]
    (if (and (>= status 200) (< status 300))
      (let [parts (-> parsed-body :candidates first :content :parts)
            ;; Thinking models (e.g. Gemma) emit {:thought true :text ""}
            ;; as the first part. Filter those out and join the rest.
            visible-parts (remove :thought parts)
            response-text (apply str (map :text visible-parts))
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

