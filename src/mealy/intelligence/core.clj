(ns mealy.intelligence.core
  "Pure intelligence routing and provider management.")

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
  [state duration-ms now-ms]
  (assoc state
         :status :backoff
         :backoff-until (+ now-ms duration-ms)))

(defn in-backoff?
  "Returns true if the provider is currently in a backoff period. Pure function."
  [state now-ms]
  (if-let [backoff-until (:backoff-until state)]
    (< now-ms backoff-until)
    false))

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
