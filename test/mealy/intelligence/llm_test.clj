(ns mealy.intelligence.llm-test
  "Tests for mealy.intelligence.llm"
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.intelligence.llm :as llm]))

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
                (let [result (llm/parse-consent response)]
                  (and (true? (:consent result))
                       (= response (:response result))))))

(defspec ^{:doc "test-parse-consent-identifies-objection"} parse-consent-identifies-objection 100
  (prop/for-all [response gen-objection-response]
                (let [result (llm/parse-consent response)]
                  (and (false? (:consent result))
                       (= response (:response result))))))
