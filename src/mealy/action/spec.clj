(ns mealy.action.spec
  "Clojure Spec definitions for core Mealy action types.
   Used by the :dry-run-eval phase to validate that LLM-generated
   handler code produces structurally correct action maps."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Common specs
;; ---------------------------------------------------------------------------

(s/def ::type keyword?)
(s/def ::state map?)
(s/def ::actions (s/coll-of map? :kind vector?))

(s/def ::handler-return
  (s/keys :req-un [::state ::actions]))

;; ---------------------------------------------------------------------------
;; :think
;; ---------------------------------------------------------------------------

(s/def :action.think/prompt string?)
(s/def :action.think/complexity #{:low :medium :high})
(s/def :action/think
  (s/keys :req-un [::type :action.think/prompt]
          :opt-un [:action.think/complexity]))

;; ---------------------------------------------------------------------------
;; :propose
;; ---------------------------------------------------------------------------

(s/def :action.propose/prompt string?)
(s/def :action/propose
  (s/keys :req-un [::type :action.propose/prompt]))

;; ---------------------------------------------------------------------------
;; :inject-event
;; ---------------------------------------------------------------------------

(s/def :action.inject-event/event vector?)
(s/def :action/inject-event
  (s/keys :req-un [::type :action.inject-event/event]))

;; ---------------------------------------------------------------------------
;; :eval
;; ---------------------------------------------------------------------------

(s/def :action.eval/code string?)
(s/def :action/eval
  (s/keys :req-un [::type :action.eval/code]))

;; ---------------------------------------------------------------------------
;; :dry-run-eval
;; ---------------------------------------------------------------------------

(s/def :action.dry-run-eval/code string?)
(s/def :action/dry-run-eval
  (s/keys :req-un [::type :action.dry-run-eval/code]))

;; ---------------------------------------------------------------------------
;; :http-request
;; ---------------------------------------------------------------------------

(s/def :action.http-request.req/url string?)
(s/def :action.http-request.req/method #{:get :post :put :delete :patch :head})
(s/def :action.http-request/req
  (s/keys :req-un [:action.http-request.req/url]
          :opt-un [:action.http-request.req/method]))
(s/def :action.http-request/callback-event keyword?)
(s/def :action/http-request
  (s/keys :req-un [::type :action.http-request/req :action.http-request/callback-event]))

;; ---------------------------------------------------------------------------
;; :bus-publish
;; ---------------------------------------------------------------------------

(s/def :action.bus-publish/topic keyword?)
(s/def :action.bus-publish/event map?)
(s/def :action/bus-publish
  (s/keys :req-un [::type :action.bus-publish/topic :action.bus-publish/event]))

;; ---------------------------------------------------------------------------
;; :start-subscription
;; ---------------------------------------------------------------------------

(s/def :action.start-subscription.config/type keyword?)
(s/def :action.start-subscription/config
  (s/keys :req-un [:action.start-subscription.config/type]))
(s/def :action/start-subscription
  (s/keys :req-un [::type :action.start-subscription/config]))

;; ---------------------------------------------------------------------------
;; :stop-subscription
;; ---------------------------------------------------------------------------

(s/def :action.stop-subscription/config map?)
(s/def :action.stop-subscription/handle keyword?)
(s/def :action/stop-subscription
  (s/keys :req-un [::type :action.stop-subscription/config :action.stop-subscription/handle]))

;; ---------------------------------------------------------------------------
;; :spawn-cell
;; ---------------------------------------------------------------------------

(s/def :action.spawn-cell/child-aim string?)
(s/def :action.spawn-cell/partition-keys (s/coll-of keyword? :kind set?))
(s/def :action.spawn-cell/bootstrap-mode #{:fresh :inherit})
(s/def :action/spawn-cell
  (s/keys :req-un [::type :action.spawn-cell/child-aim]
          :opt-un [:action.spawn-cell/partition-keys :action.spawn-cell/bootstrap-mode]))

;; ---------------------------------------------------------------------------
;; :app-event
;; ---------------------------------------------------------------------------

(s/def :action.app-event/event-type keyword?)
(s/def :action/app-event
  (s/keys :req-un [::type :action.app-event/event-type]))

;; ---------------------------------------------------------------------------
;; Registry & validation
;; ---------------------------------------------------------------------------

(def action-type->spec
  "Registry mapping action :type keywords to their qualified spec names."
  {:think              :action/think
   :propose            :action/propose
   :inject-event       :action/inject-event
   :eval               :action/eval
   :dry-run-eval       :action/dry-run-eval
   :http-request       :action/http-request
   :bus-publish         :action/bus-publish
   :start-subscription :action/start-subscription
   :stop-subscription  :action/stop-subscription
   :spawn-cell         :action/spawn-cell
   :app-event          :action/app-event})

(defn validate-actions
  "Validates a vector of action maps against their registered specs.
   Returns nil on success, or a vector of
   {:action <map> :explanation <string>} for each failing action."
  [actions]
  (let [errors (for [action actions
                     :let [spec-name (get action-type->spec (:type action))]
                     :when spec-name
                     :when (not (s/valid? spec-name action))]
                 {:action action
                  :explanation (s/explain-str spec-name action)})]
    (when (seq errors)
      (vec errors))))

(defn describe-action-specs
  "Returns a human-readable string describing all registered action specs.
   Used to inject spec documentation into LLM prompts."
  []
  (let [descriptions
        (for [[action-type spec-name] (sort-by key action-type->spec)
              :let [form (s/form spec-name)]]
          (str "  " action-type ": " (pr-str form)))]
    (str/join "\n" descriptions)))
