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

(deftest test-handle-observation-idle
  (testing "[:observation data] event while :idle appends data to state's observations, transitions to :evaluating, and yields an :http-request command"
    (let [c (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          event [:observation {:temp 98.6}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= [{:temp 98.6}] (:observations new-state)))
      (is (= :evaluating (:phase new-state)))
      (is (= :p1 (get-in new-state [:memory :active-provider])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :consent-evaluated (:callback-event (first actions)))))))

(deftest test-handle-observation-reflex
  (testing "[:observation data] matching a reflex yields the reflex command and remains :idle"
    (let [c (cell/make-cell "Survive" {:reflexes {:cpu-temp-high {:type :throttle-cpu}}})
          event [:observation {:type :cpu-temp-high :value 95}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= [{:type :cpu-temp-high :value 95}] (:observations new-state)))
      (is (= :idle (:phase new-state)))
      (is (= 1 (count actions)))
      (is (= :throttle-cpu (:type (first actions)))))))

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

(deftest test-handle-consent-evaluated-positive
  (testing "[:consent-evaluated data] with positive consent transitions to :acting and yields an :execute-action command"
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

(deftest test-handle-evaluation-error
  (testing "[:evaluation-error data] transitions to :idle, records the error, and yields no actions"
    (let [c (assoc (cell/make-cell "Survive" {}) :phase :evaluating)
          event [:evaluation-error {:reason "Timeout"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :idle (:phase new-state)))
      (is (= "Timeout" (:last-error new-state)))
      (is (empty? actions)))))

(deftest test-handle-unknown-event
  (testing "Unknown event returns state unchanged and empty actions"
    (let [c (cell/make-cell "Survive" {})
          event [:unknown-event {:foo :bar}]
          result (reducer/handle-event c event)]
      (is (= c (:state result)))
      (is (= [] (:actions result))))))

(deftest test-handle-eval-success-persists-policy
  (testing "[:observation data] with :type :eval-success and :code appends code to state's active-policies"
    (let [c (cell/make-cell "Survive" {})
          event [:observation {:type :eval-success :result :ok :code "(defmethod execute :new-skill ...)"}]
          result (reducer/handle-event c event)
          new-state (:state result)]
      (is (= ["(defmethod execute :new-skill ...)"] (get-in new-state [:memory :active-policies]))))))

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

(deftest test-handle-propose-policy
  (testing "[:propose-policy data] event while :idle appends code to state's memory :proposed-policies, transitions to :evaluating, and yields an :http-request command"
    (let [c (cell/make-cell "Survive" {:providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
          event [:propose-policy {:code "(defmethod execute :new-skill ...)"}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= ["(defmethod execute :new-skill ...)"] (get-in new-state [:memory :proposed-policies])))
      (is (= :evaluating (:phase new-state)))
      (is (= :p1 (get-in new-state [:memory :active-provider])))
      (is (= 1 (count actions)))
      (is (= :http-request (:type (first actions))))
      (is (= :policy-consent-evaluated (:callback-event (first actions)))))))

(deftest test-handle-policy-consent-evaluated-positive
  (testing "[:policy-consent-evaluated data] with positive consent transitions to :acting, pops the policy, and yields an :execute-action command for :eval"
    (let [c (assoc (cell/make-cell "Survive" {:proposed-policies ["(defmethod execute :new-skill ...)"]
                                              :active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I CONSENT to this policy\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
          event [:policy-consent-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :acting (:phase new-state)))
      (is (= [] (get-in new-state [:memory :proposed-policies])))
      (is (= 1 (count actions)))
      (is (= {:type :eval :code "(defmethod execute :new-skill ...)"} (first actions))))))

(deftest test-handle-policy-consent-evaluated-objection
  (testing "[:policy-consent-evaluated data] with objection transitions to :idle, pops the policy, and yields no actions"
    (let [c (assoc (cell/make-cell "Survive" {:proposed-policies ["(defmethod execute :new-skill ...)"]
                                              :active-provider :p1
                                              :providers {:p1 {:adapter-type :gemini :status :healthy :budget 1000 :complexity :high}}})
                   :phase :evaluating)
          json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"I have an OBJECTION to this policy\"}]}}],\"usageMetadata\":{\"totalTokenCount\":10}}"
          event [:policy-consent-evaluated {:response {:status 200 :body json-body}}]
          result (reducer/handle-event c event)
          new-state (:state result)
          actions (:actions result)]
      (is (= :idle (:phase new-state)))
      (is (= [] (get-in new-state [:memory :proposed-policies])))
      (is (empty? actions)))))
