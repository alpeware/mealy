(ns mealy.ooda.actuator-test
  "Tests for mealy.ooda.actuator"
  (:require [clojure.core.async :as async :refer [<!! >!!]]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.ooda.actuator :as actuator]))

(def gen-consent-response
  "Generator for strings that should parse as consent."
  (gen/fmap (fn [s] (str "CONSENT: " s)) gen/string-alphanumeric))

(def gen-objection-response
  "Generator for strings that should parse as an objection."
  (gen/fmap (fn [s] (str "OBJECTION: " s)) gen/string-alphanumeric))

(defspec ^{:doc "test-parse-consent-identifies-consent"} parse-consent-identifies-consent 100
  (prop/for-all [response gen-consent-response]
                (let [result (actuator/parse-consent response)]
                  (and (true? (:consent result))
                       (= response (:response result))))))

(defspec ^{:doc "test-parse-consent-identifies-objection"} parse-consent-identifies-objection 100
  (prop/for-all [response gen-objection-response]
                (let [result (actuator/parse-consent response)]
                  (and (false? (:consent result))
                       (= response (:response result))))))

(deftest start-actuator-test
  (testing "actuator processes commands and emits observations"
    (let [cmd-chan (async/chan 10)
          cell-in-chan (async/chan 10)
          llm-fn (fn [prompt] (str "CONSENT: " prompt "-response"))]

      ;; Start the actuator
      (actuator/start-actuator cmd-chan cell-in-chan llm-fn)

      ;; Send a command to the actuator
      (>!! cmd-chan {:type :evaluate-prompt :prompt "test-prompt"})

      ;; Read the resulting observation from the cell's input channel
      (let [observation (<!! cell-in-chan)]
        (is (= :observation (first observation)))
        (let [obs-data (second observation)]
          (is (= :evaluation-result (:type obs-data)))
          (is (true? (:consent obs-data)))
          (is (= "CONSENT: test-prompt-response" (:response obs-data)))))

      ;; Clean up
      (async/close! cmd-chan))))
