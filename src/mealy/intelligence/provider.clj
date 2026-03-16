(ns mealy.intelligence.provider
  "Stateful go-loop component that wraps a specific LLM API/Local Model."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]))

(defn initial-state
  "Returns the initial state for a provider actor based on the config map."
  [config]
  {:provider-id (:provider-id config)
   :budget (:budget config)
   :rpm (:rpm config)
   :status :healthy
   :backoff-until nil})

(defn deduct-budget
  "Deducts `tokens` from the provider's budget. Pure function."
  [state tokens]
  (assoc state :budget (- (:budget state) tokens)))

(defn set-backoff
  "Puts the provider into a backoff state for `duration-ms`. Pure function."
  [state duration-ms]
  (assoc state
         :status :backoff
         :backoff-until (+ (System/currentTimeMillis) duration-ms)))

(defn in-backoff?
  "Returns true if the provider is currently in a backoff period. Pure function."
  [state now-ms]
  (if-let [backoff-until (:backoff-until state)]
    (< now-ms backoff-until)
    false))

(defn handle-evaluate-command
  "Handles an evaluate command, determining if the provider can process it.
   Returns `{:can-evaluate? boolean :state new-state :reason ...}`"
  [state estimated-tokens now-ms]
  (cond
    (in-backoff? state now-ms)
    {:can-evaluate? false :state state :reason :in-backoff}

    (< (:budget state) estimated-tokens)
    {:can-evaluate? false :state state :reason :insufficient-budget}

    :else
    {:can-evaluate? true :state state}))

(defn start-provider
  "Spawns a go-loop that manages the provider's state and processes commands.
   `config` specifies initial budget and limits.
   `cmd-chan` receives `{:type :status :reply-chan c}` and
                       `{:type :evaluate :prompt p :estimated-tokens t :reply-chan c}`.
   `llm-fn` is an async function `(fn [prompt]) -> channel` that returns
            `{:tokens n :response r}` or `{:error true :backoff-ms ms :reason r}`."
  [config cmd-chan llm-fn]
  (let [response-chan (async/chan 10)]
    (go-loop [state (initial-state config)]
      (let [[val port] (async/alts! [cmd-chan response-chan])
            now (System/currentTimeMillis)
            ;; If the backoff has expired, we switch back to healthy
            state (if (and (= (:status state) :backoff)
                           (not (in-backoff? state now)))
                    (assoc state :status :healthy :backoff-until nil)
                    state)]

        (cond
          ;; cmd-chan closed or response-chan closed unexpectedly
          (nil? val) nil

          ;; Message from cmd-chan
          (= port cmd-chan)
          (let [{:keys [type reply-chan prompt estimated-tokens]} val]
            (case type
              :status
              (do
                (>! reply-chan state)
                (recur state))

              :evaluate
              (let [eval-check (handle-evaluate-command state (or estimated-tokens 0) now)]
                (if (:can-evaluate? eval-check)
                  ;; Valid to evaluate, kick off the async llm-fn
                  (let [llm-res-chan (llm-fn prompt)]
                    (async/go
                      (let [res (<! llm-res-chan)]
                        ;; Tag the response with the reply-chan so the main loop can send it back
                        (>! response-chan (assoc res :reply-chan reply-chan))))
                    (recur (:state eval-check)))

                  ;; Cannot evaluate
                  (do
                    (>! reply-chan {:status :error :reason (:reason eval-check)})
                    (recur (:state eval-check)))))))

          ;; Message from internal response-chan (llm-fn completed)
          (= port response-chan)
          (let [{:keys [tokens reply-chan error backoff-ms] :as res} val
                ;; Clean up the internal fields before sending back
                clean-res (dissoc res :reply-chan)
                new-state (cond
                            error (if backoff-ms
                                    (set-backoff state backoff-ms)
                                    state)
                            tokens (deduct-budget state tokens)
                            :else state)]
            (>! reply-chan clean-res)
            (recur new-state)))))))
