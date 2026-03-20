(ns mealy.intelligence.router
  "Multiplexing go-loop between Cell Actuators and Providers. Routes evaluate-prompt commands to the optimal provider."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]))

(def complexity-scores
  "Maps qualitative complexity levels to quantitative scores."
  {:low 1
   :medium 2
   :high 3})

(defn score-provider
  "Scores a provider based on its state, required complexity, and estimated tokens.
   A negative score means the provider cannot or should not be used.
   Higher positive score is better."
  [provider-state required-complexity estimated-tokens]
  (if (or (= (:status provider-state) :backoff)
          (< (:budget provider-state) estimated-tokens))
    -1
    (let [provider-c (get complexity-scores (:complexity provider-state) 0)
          required-c (get complexity-scores required-complexity 0)
          ;; Score logic:
          ;; We prefer matching complexity (delta 0).
          ;; If provider > required (e.g. provider high, required low), delta > 0. It's fine but maybe wasteful.
          ;; If provider < required (e.g. provider low, required high), delta < 0. We penalize this heavily.
          delta (- provider-c required-c)
          complexity-score (cond
                             (= delta 0) 100
                             (> delta 0) (- 100 (* delta 10)) ;; slightly penalize over-provisioning
                             (< delta 0) (max 0 (- 10 (* (- delta) 20))))] ;; heavily penalize under-provisioning, but keep non-negative
      complexity-score)))

(defn select-best-provider
  "Selects the optimal provider ID given a map of provider states."
  [provider-states required-complexity estimated-tokens]
  (let [scored (map (fn [[k state]]
                      [k (score-provider state required-complexity estimated-tokens)])
                    provider-states)
        valid (filter #(>= (second %) 0) scored)]
    (when (seq valid)
      (first (apply max-key second valid)))))

(defn start-router
  "Spawns a go-loop that listens on `cmd-chan` for `{:type :evaluate ...}` commands.
   `registry` is a map of `{:provider-id {:chan ch :complexity :low|:medium|:high}}`.
   The router fetches status from all providers, selects the best one, and forwards the command."
  [registry cmd-chan]
  (go-loop []
    (let [val (<! cmd-chan)]
      (when val
        (let [{:keys [type messages estimated-tokens complexity reply-chan]} val]
          (if (= type :evaluate)
            (let [;; Send status requests to all providers concurrently (must be eager)
                  status-chans (doall
                                (map (fn [[pid info]]
                                       (let [c (async/chan 1)]
                                         (async/go (>! (:chan info) {:type :status :reply-chan c}))
                                         [pid c]))
                                     registry))
                  ;; Collect all statuses sequentially in the go block (no <! in fn)
                  provider-states (loop [chans status-chans
                                         states {}]
                                    (if-let [[pid c] (first chans)]
                                      (let [status (<! c)]
                                        (recur (rest chans)
                                               (assoc states pid (assoc status :complexity (get-in registry [pid :complexity])))))
                                      states))
                  best-pid (select-best-provider provider-states (or complexity :low) (or estimated-tokens 0))]

              (if best-pid
                ;; Forward evaluate command to the best provider
                (let [provider-chan (get-in registry [best-pid :chan])]
                  (>! provider-chan {:type :evaluate
                                     :messages messages
                                     :estimated-tokens estimated-tokens
                                     :reply-chan reply-chan}))
                ;; No valid provider found
                (>! reply-chan {:status :error :reason :no-available-provider})))
            ;; Ignore non-evaluate commands
            nil)
          (recur))))))