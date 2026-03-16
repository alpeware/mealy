(ns mealy.ooda.actuator
  "The IO boundary for submitting prompts to the LLM and parsing the Consent result back into the Cell."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [clojure.string :as str]))

(defn parse-consent
  "Pure function to parse the LLM's response string.
  Returns a map containing :consent (boolean) and the original :response."
  [response]
  (let [upper (str/upper-case response)
        consent? (and (str/includes? upper "CONSENT")
                      (not (str/includes? upper "OBJECTION")))]
    {:consent consent?
     :response response}))

(defn start-actuator
  "Spawns a go-loop acting as the IO boundary.
  Listens on `cmd-chan` for `{:type :evaluate-prompt :prompt \"...\"}` commands.
  Sends an `{:type :evaluate ...}` command to `router-chan` with a `reply-chan`.
  When the response arrives, parses the result using `parse-consent` and places
  an observation on `cell-in-chan`.
  Errors from the router are returned as `[:observation {:type :evaluation-error :reason ...}]`."
  [cmd-chan cell-in-chan router-chan]
  (go-loop []
    (if-let [cmd (<! cmd-chan)]
      (do
        (when (and (map? cmd) (= (:type cmd) :evaluate-prompt))
          (let [reply-chan (async/chan 1)]
            ;; Spawn a dedicated go-loop for handling the evaluation to avoid
            ;; blocking the main actuator loop from receiving more commands.
            (async/go
              (>! router-chan {:type :evaluate
                               :prompt (:prompt cmd)
                               :estimated-tokens (:estimated-tokens cmd)
                               :complexity (:complexity cmd)
                               :reply-chan reply-chan})
              (let [response (<! reply-chan)]
                (if (or (= (:status response) :error) (:error response))
                  (>! cell-in-chan [:observation {:type :evaluation-error
                                                  :reason (:reason response)}])
                  (let [parsed (parse-consent (:response response))]
                    (>! cell-in-chan [:observation (assoc parsed :type :evaluation-result)])))))))
        (recur))
      ;; Exit when cmd-chan is closed
      nil)))
