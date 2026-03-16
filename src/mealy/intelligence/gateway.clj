(ns mealy.intelligence.gateway
  "The generic IO boundary for submitting prompts to the Intelligence Router
  and forwarding raw LLM responses back into the Cell."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]))

(defn start-gateway
  "Spawns a go-loop acting as the generic IO boundary.
  Listens on `cmd-chan` for `{:type :llm-request :prompt \"...\" :callback-event :some-event}` commands.
  Sends an `{:type :evaluate ...}` command to `router-chan` with a `reply-chan`.
  When the response arrives, it places an observation on `cell-in-chan`.
  Errors from the router are returned as `[:observation {:type :evaluation-error :reason ...}]`.
  Successes are returned as `[:observation {:type :callback-event :response \"...\"}]`."
  [cmd-chan cell-in-chan router-chan]
  (go-loop []
    (if-let [cmd (<! cmd-chan)]
      (do
        (when (and (map? cmd) (= (:type cmd) :llm-request))
          (let [reply-chan (async/chan 1)
                callback-event (:callback-event cmd)]
            ;; Spawn a dedicated go-loop for handling the evaluation to avoid
            ;; blocking the main gateway loop from receiving more commands.
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
                  (>! cell-in-chan [:observation {:type callback-event
                                                  :response (:response response)}]))))))
        (recur))
      ;; Exit when cmd-chan is closed
      nil)))