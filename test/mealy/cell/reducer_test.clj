(ns mealy.cell.reducer-test
  "Tests for mealy.cell.reducer"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.core :as cell]
            [mealy.cell.reducer :as reducer]))

(defspec ^{:doc "Generative invariant: handle-event always returns a valid state and actions vector."}
  test-handle-event-invariant 100
  (prop/for-all [aim (gen/one-of [(gen/return "a string") (gen/return :a-keyword) (gen/return 'a-symbol)])
                 memory (gen/map gen/keyword gen/any)
                 phase (gen/elements [:idle :evaluating :acting])
                 event (gen/tuple gen/keyword gen/any)]
                (let [c (assoc (cell/make-cell aim memory) :phase phase)
                      result (reducer/handle-event c event)]
                  (and (map? result)
                       (contains? result :state)
                       (contains? result :actions)
                       (map? (:state result))
                       (vector? (:actions result))
                       (contains? (:state result) :phase)))))

;; ---------------------------------------------------------------------------
;; :observation — now accumulates only, no longer triggers orient
;; ---------------------------------------------------------------------------

(deftest test-handle-observation-accumulates
  (testing "[:observation data] event accumulates the observation and yields no actions"
    (let [c (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          event [:observation {:temp 98.6}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= [{:temp 98.6}] (:observations new-state)))
      (is (= :idle (:phase new-state)))
      (is (empty? actions)))))

(deftest test-handle-observation-not-idle
  (testing "[:observation data] event while not :idle buffers observation but yields no actions"
    (let [c (assoc (cell/make-cell "Survive" {}) :phase :evaluating)
          event [:observation {:temp 98.6}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= [{:temp 98.6}] (:observations new-state)))
      (is (= :evaluating (:phase new-state)))
      (is (empty? actions)))))

;; ---------------------------------------------------------------------------
;; :orient — triggers evaluation or reflex
;; ---------------------------------------------------------------------------

(deftest test-handle-orient-idle
  (testing "[:orient] while :idle with observations transitions to :evaluating and yields :http-request"
    (let [c (-> (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                (update :observations conj {:temp 98.6}))
          event [:orient {}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :evaluating (:phase new-state)))
      (is (= :p1 (get-in new-state [:memory :active-provider])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :orient-evaluated (:callback-event (first actions)))))))

(deftest test-handle-orient-reflex
  (testing "[:orient] matching a reflex yields the reflex command and remains :idle"
    (let [c (-> (cell/make-cell "Survive" {:reflexes {:cpu-temp-high {:type :throttle-cpu}}})
                (update :observations conj {:type :cpu-temp-high :value 95}))
          event [:orient {}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :idle (:phase new-state)))
      (is (= 1 (count actions)))
      (is (= :throttle-cpu (:type (first actions)))))))

;; ---------------------------------------------------------------------------
;; :consent-evaluated
;; ---------------------------------------------------------------------------

(deftest test-handle-consent-evaluated-positive
  (testing "[:consent-evaluated data] with positive consent transitions to :acting and yields no commands"
    (let [c (assoc (cell/make-cell "Survive" {:active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I CONSENT to this action\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
          event [:consent-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :acting (:phase new-state)))
      (is (= 0 (count actions)))
      (is (= 985 (get-in new-state [:memory :providers :p1 :budget])))
      (is (nil? (get-in new-state [:memory :active-provider]))))))

(deftest test-handle-consent-evaluated-objection
  (testing "[:consent-evaluated data] with objection transitions to :idle and yields no actions"
    (let [c (assoc (cell/make-cell "Survive" {:active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I have an OBJECTION to this action\"}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}"
          event [:consent-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :idle (:phase new-state)))
      (is (empty? actions))
      (is (= 990 (get-in new-state [:memory :providers :p1 :budget])))
      (is (nil? (get-in new-state [:memory :active-provider]))))))

;; ---------------------------------------------------------------------------
;; :evaluation-error
;; ---------------------------------------------------------------------------

(deftest test-handle-evaluation-error
  (testing "[:evaluation-error data] after exhausting retries transitions to :idle, records the error, and yields no actions"
    (let [c (-> (cell/make-cell "Survive" {:eval-retries 3})
                (assoc :phase :generating-code))
          event [:evaluation-error {:reason "Timeout" :code "(bad-code)"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :idle (:phase new-state)))
      (is (= "Timeout" (:last-error new-state)))
      (is (empty? actions)))))

;; ---------------------------------------------------------------------------
;; Unknown event
;; ---------------------------------------------------------------------------

(deftest test-handle-unknown-event
  (testing "Unknown event returns state unchanged and empty actions"
    (let [c (cell/make-cell "Survive" {})
          event [:unknown-event {:foo :bar}]
          result (reducer/handle-event c event)]
      (is (= c (:state result)))
      (is (= [] (:actions result))))))

;; ---------------------------------------------------------------------------
;; :proposal — General proposal (replaces :propose-policy)
;; ---------------------------------------------------------------------------

(deftest test-handle-proposal
  (testing "[:proposal data] event appends prompt to :pending-proposals, transitions to :evaluating, yields :http-request"
    (let [c (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          event [:proposal {:prompt "(defmethod execute :new-skill ...)"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= ["(defmethod execute :new-skill ...)"] (get-in new-state [:memory :pending-proposals])))
      (is (= :evaluating (:phase new-state)))
      (is (= :p1 (get-in new-state [:memory :active-provider])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :proposal-evaluated (:callback-event (first actions)))))))

(deftest test-handle-proposal-evaluated-positive
  (testing "[:proposal-evaluated data] with positive consent pops the proposal, transitions to :generating-code, yields :http-request"
    (let [c (assoc (cell/make-cell "Survive" {:pending-proposals ["(defmethod execute :new-skill ...)"]
                                              :active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 10000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I CONSENT to this proposal\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
          event [:proposal-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :generating-code (:phase new-state)))
      (is (= [] (get-in new-state [:memory :pending-proposals])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :code-generated (:callback-event (first actions)))))))

(deftest test-handle-proposal-evaluated-objection
  (testing "[:proposal-evaluated data] with objection transitions to :idle, pops the proposal, yields no actions"
    (let [c (assoc (cell/make-cell "Survive" {:pending-proposals ["(defmethod execute :new-skill ...)"]
                                              :active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I have an OBJECTION to this proposal\"}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}"
          event [:proposal-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :idle (:phase new-state)))
      (is (= [] (get-in new-state [:memory :pending-proposals])))
      (is (empty? actions)))))

;; ---------------------------------------------------------------------------
;; :propose-policy / :policy-consent-evaluated — Legacy compat
;; ---------------------------------------------------------------------------

(deftest test-handle-propose-policy-legacy
  (testing "[:propose-policy data] redirects to :proposal"
    (let [c (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          event [:propose-policy {:prompt "(defmethod execute :new-skill ...)"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= ["(defmethod execute :new-skill ...)"] (get-in new-state [:memory :pending-proposals])))
      (is (= :evaluating (:phase new-state)))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :proposal-evaluated (:callback-event (first actions)))))))

(deftest test-handle-policy-consent-evaluated-positive-legacy
  (testing "[:policy-consent-evaluated data] redirects to :proposal-evaluated"
    (let [c (assoc (cell/make-cell "Survive" {:pending-proposals ["(defmethod execute :new-skill ...)"]
                                              :active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 10000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I CONSENT to this policy\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
          event [:policy-consent-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :generating-code (:phase new-state)))
      (is (= [] (get-in new-state [:memory :pending-proposals])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :code-generated (:callback-event (first actions)))))))

;; ---------------------------------------------------------------------------
;; :policy-change — Two-phase Sociocratic consent workflow
;; ---------------------------------------------------------------------------

(deftest test-handle-policy-change-phase1-self-evaluate
  (testing "[:policy-change] enters :evaluating-policy and routes LLM request for self-evaluation"
    (let [c (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          event [:policy-change {:policy "Always respond in English"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :evaluating-policy (:phase new-state)))
      (is (= "Always respond in English" (get-in new-state [:memory :pending-policy-change])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :policy-self-evaluated (:callback-event (first actions)))))))

(deftest test-handle-policy-self-evaluated-consent-root
  (testing "[:policy-self-evaluated] with cell consent on root (:anchor parent) enters :awaiting-consent and emits :app-event"
    (let [c (-> (cell/make-cell "Survive" {:pending-policy-change "Always respond in English"
                                           :active-provider :p1
                                           :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                (assoc :phase :evaluating-policy))
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"CONSENT: Consistent with aim.\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
          event [:policy-self-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :awaiting-consent (:phase new-state)))
      (is (= "Always respond in English" (get-in new-state [:memory :pending-policy-change])))
      (is (= 1 (count actions)))
      (is (= :app-event (:type (first actions))))
      (is (= :consent-request (:event-type (first actions))))
      (is (= "Always respond in English" (:policy (first actions)))))))

(deftest test-handle-policy-self-evaluated-consent-child
  (testing "[:policy-self-evaluated] with cell consent on child cell enters :awaiting-consent and emits :bus-publish"
    (let [c (-> (cell/make-cell "Survive" {:pending-policy-change "Limit tokens"
                                           :active-provider :p1
                                           :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                (assoc :phase :evaluating-policy
                       :parent :parent-cell-id))
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"CONSENT: Acceptable.\"}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}"
          event [:policy-self-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :awaiting-consent (:phase new-state)))
      (is (= 1 (count actions)))
      (is (= :bus-publish (:type (first actions))))
      (is (= :parent-cell-id (:topic (first actions)))))))

(deftest test-handle-policy-self-evaluated-objection
  (testing "[:policy-self-evaluated] with cell objection aborts and returns to :idle"
    (let [c (-> (cell/make-cell "Survive" {:pending-policy-change "Bad policy"
                                           :active-provider :p1
                                           :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                (assoc :phase :evaluating-policy))
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OBJECTION: Contradicts aim.\"}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}"
          event [:policy-self-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)]
      (is (= :idle (:phase new-state)))
      (is (nil? (get-in new-state [:memory :pending-policy-change]))))))

(deftest test-handle-consent-granted
  (testing "[:consent-granted] while :awaiting-consent adds the policy and returns to :idle"
    (let [c (-> (cell/make-cell "Survive" {:pending-policy-change "Always respond in English"})
                (assoc :phase :awaiting-consent))
          event [:consent-granted {}]
          result (reducer/handle-event c event)
          new-state (:state result)]
      (is (= :idle (:phase new-state)))
      (is (= ["Always respond in English"] (:policies new-state)))
      (is (nil? (get-in new-state [:memory :pending-policy-change]))))))

(deftest test-handle-consent-rejected
  (testing "[:consent-rejected] while :awaiting-consent discards change and returns to :idle"
    (let [c (-> (cell/make-cell "Survive" {:pending-policy-change "Always respond in English"})
                (assoc :phase :awaiting-consent))
          event [:consent-rejected {}]
          result (reducer/handle-event c event)
          new-state (:state result)]
      (is (= :idle (:phase new-state)))
      (is (empty? (:policies new-state)))
      (is (nil? (get-in new-state [:memory :pending-policy-change]))))))

;; ---------------------------------------------------------------------------
;; parse-consent
;; ---------------------------------------------------------------------------

(def gen-consent-response
  "Generator for strings that should parse as consent."
  (gen/fmap (fn [s] (str "CONSENT: " s)) gen/string-alphanumeric))

(def gen-objection-response
  "Generator for strings that should parse as an objection."
  (gen/fmap (fn [s] (str "OBJECTION: " s)) gen/string-alphanumeric))

(defspec ^{:doc "test-parse-consent-identifies-consent"} parse-consent-identifies-consent 100
  (prop/for-all [response gen-consent-response]
                (let [result (reducer/parse-consent response)]
                  (and (true? (:consent result))
                       (= response (:response result))))))

(defspec ^{:doc "test-parse-consent-identifies-objection"} parse-consent-identifies-objection 100
  (prop/for-all [response gen-objection-response]
                (let [result (reducer/parse-consent response)]
                  (and (false? (:consent result))
                       (= response (:response result))))))
