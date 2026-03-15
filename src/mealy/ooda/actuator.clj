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
  Passes the prompt to `llm-fn` (a side-effecting function, e.g., an API call).
  Parses the result using `parse-consent` and places an observation
  `[:observation {:type :evaluation-result :consent true/false :response \"...\"}]`
  on `cell-in-chan`."
  [cmd-chan cell-in-chan llm-fn]
  (go-loop []
    (if-let [cmd (<! cmd-chan)]
      (do
        (when (and (map? cmd) (= (:type cmd) :evaluate-prompt))
          (let [prompt (:prompt cmd)
                response (llm-fn prompt)
                parsed (parse-consent response)]
            (>! cell-in-chan [:observation (assoc parsed :type :evaluation-result)])))
        (recur))
      ;; Exit when cmd-chan is closed
      nil)))
