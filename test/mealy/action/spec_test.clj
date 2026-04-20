(ns mealy.action.spec-test
  "Generative (property-based) tests for action specs.
   Verifies structural contracts that the dry-run phase enforces."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as tgen]
            [clojure.test.check.properties :as prop]
            [mealy.action.spec :as spec]))

;; ---------------------------------------------------------------------------
;; Property: generated action maps always pass validate-actions
;; ---------------------------------------------------------------------------

(defn- gen-valid-action
  "Generator for a single valid action map of the given type."
  [action-type spec-name]
  (tgen/fmap #(assoc % :type action-type)
             (s/gen spec-name)))

(def gen-valid-actions
  "Generator for a vector of valid action maps drawn from all registered types."
  (tgen/bind
   (tgen/not-empty
    (tgen/vector
     (tgen/elements (vec spec/action-type->spec))))
   (fn [type-spec-pairs]
     (apply tgen/tuple
            (map (fn [[action-type spec-name]]
                   (gen-valid-action action-type spec-name))
                 type-spec-pairs)))))

(defspec ^{:doc "Generated action maps always pass validate-actions."}
  generated-actions-always-validate 50
  (prop/for-all [actions gen-valid-actions]
                (nil? (spec/validate-actions (vec actions)))))

;; ---------------------------------------------------------------------------
;; Property: handler-return spec round-trips with generated data
;; ---------------------------------------------------------------------------

(defspec ^{:doc "Handler return spec round-trips with generated data."}
  handler-return-round-trip 50
  (prop/for-all [state (s/gen ::spec/state)
                 actions (tgen/vector
                          (tgen/one-of
                           [(gen-valid-action :think :action/think)
                            (gen-valid-action :propose :action/propose)
                            (gen-valid-action :eval :action/eval)]))]
                (s/valid? ::spec/handler-return {:state state :actions (vec actions)})))

;; ---------------------------------------------------------------------------
;; Unit: known-bad :http-request from the bug report
;; ---------------------------------------------------------------------------

(deftest rejects-malformed-http-request
  (testing "the exact buggy :http-request from the LLM is rejected"
    (let [bad-action {:type :http-request
                      :url "https://hacker-news.firebaseio.com/v0/item/123.json"
                      :on-success [:store-story-details]}
          errors (spec/validate-actions [bad-action])]
      (is (some? errors) "malformed :http-request must be rejected")
      (is (= 1 (count errors)))
      (is (= bad-action (:action (first errors))))))

  (testing "a well-formed :http-request passes"
    (let [good-action {:type :http-request
                       :req {:url "https://hacker-news.firebaseio.com/v0/item/123.json"
                             :method :get}
                       :callback-event :store-story-details}]
      (is (nil? (spec/validate-actions [good-action]))))))

;; ---------------------------------------------------------------------------
;; Unit: unknown types pass through (extensibility)
;; ---------------------------------------------------------------------------

(deftest unknown-type-passthrough
  (testing "actions with unregistered types are not rejected"
    (let [custom-action {:type :my-custom-skill :data "whatever"}]
      (is (nil? (spec/validate-actions [custom-action]))))))

;; ---------------------------------------------------------------------------
;; Unit: empty actions vector is valid
;; ---------------------------------------------------------------------------

(deftest empty-actions-valid
  (testing "an empty actions vector passes validation"
    (is (nil? (spec/validate-actions [])))))

;; ---------------------------------------------------------------------------
;; Unit: handler-return spec validation
;; ---------------------------------------------------------------------------

(deftest handler-return-spec
  (testing "valid handler return"
    (is (s/valid? ::spec/handler-return
                  {:state {:aim "test"} :actions []})))

  (testing "handler return missing :state"
    (is (not (s/valid? ::spec/handler-return
                       {:actions []}))))

  (testing "handler return missing :actions"
    (is (not (s/valid? ::spec/handler-return
                       {:state {:aim "test"}}))))

  (testing "handler return with actions as list instead of vector"
    (is (not (s/valid? ::spec/handler-return
                       {:state {:aim "test"} :actions '()})))))

;; ---------------------------------------------------------------------------
;; Unit: validate individual core action specs
;; ---------------------------------------------------------------------------

(deftest validate-think-spec
  (is (s/valid? :action/think {:type :think :prompt "What next?"}))
  (is (not (s/valid? :action/think {:type :think})))
  (is (not (s/valid? :action/think {:type :think :prompt 42}))))

(deftest validate-propose-spec
  (is (s/valid? :action/propose {:type :propose :prompt "Write a handler"}))
  (is (not (s/valid? :action/propose {:type :propose}))))

(deftest validate-inject-event-spec
  (is (s/valid? :action/inject-event {:type :inject-event :event [:heartbeat {:ts 1}]}))
  (is (not (s/valid? :action/inject-event {:type :inject-event :event "not-a-vector"}))))

(deftest validate-http-request-spec
  (testing "requires :req map with :url and a :callback-event keyword"
    (is (s/valid? :action/http-request
                  {:type :http-request
                   :req {:url "https://example.com"}
                   :callback-event :my-response}))
    (is (s/valid? :action/http-request
                  {:type :http-request
                   :req {:url "https://example.com" :method :post}
                   :callback-event :my-response})))

  (testing "rejects bare :url at top level"
    (is (not (s/valid? :action/http-request
                       {:type :http-request
                        :url "https://example.com"
                        :on-success :my-response}))))

  (testing "rejects vector callback"
    (is (not (s/valid? :action/http-request
                       {:type :http-request
                        :req {:url "https://example.com"}
                        :callback-event [:my-response]})))))

(deftest validate-spawn-cell-spec
  (is (s/valid? :action/spawn-cell
                {:type :spawn-cell :child-aim "Monitor RSS"}))
  (is (s/valid? :action/spawn-cell
                {:type :spawn-cell :child-aim "Monitor RSS"
                 :partition-keys #{:feeds} :bootstrap-mode :fresh}))
  (is (not (s/valid? :action/spawn-cell
                     {:type :spawn-cell}))))

(deftest validate-app-event-spec
  (is (s/valid? :action/app-event
                {:type :app-event :event-type :consent-request :policy "Be kind"}))
  (is (not (s/valid? :action/app-event
                     {:type :app-event}))))

;; ---------------------------------------------------------------------------
;; Unit: describe-action-specs produces non-empty output
;; ---------------------------------------------------------------------------

(deftest describe-action-specs-output
  (testing "describe-action-specs returns a non-empty string with all types"
    (let [desc (spec/describe-action-specs)]
      (is (string? desc))
      (is (pos? (count desc)))
      (doseq [action-type (keys spec/action-type->spec)]
        (is (str/includes? desc (str action-type))
            (str "description should mention " action-type))))))
