(ns mealy.intelligence.adapters.llama-test
  "Tests for the Llama Provider Adapter"
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.intelligence.adapters.llama :as llama]))

#_{:clj-kondo/ignore [:missing-docstring]}
(defspec build-request-test
  100
  (prop/for-all [url gen/string-alphanumeric
                 model gen/string-alphanumeric
                 prompt gen/string-alphanumeric]
                (let [req (llama/build-request url model prompt)
                      system-prompt "You are the deterministic cognitive routing engine for an autonomous agent. Your ONLY purpose is to evaluate the user's provided State and Proposed Policy. You do not converse. You do not offer help. If the Proposed Policy does not critically harm the Aim or violate Memory, you MUST output exactly: 'CONSENT: [brief reason]'. If it violates a critical constraint, output exactly: 'OBJECTION: [reason]'."
                      expected-body (json/generate-string
                                     {:messages [{:role "system" :content system-prompt}
                                                 {:role "user" :content prompt}]
                                      :max_tokens 2048
                                      :stop ["<|im_end|>" "<|endoftext|>"]
                                      :stream false})]
                  (and
                   (= (:method req) :post)
                   (= (:url req) (str url "/v1/chat/completions"))
                   (= (get-in req [:headers "Content-Type"]) "application/json")
                   (= (:body req) expected-body)))))

(deftest parse-response-test
  (testing "Successful response parsing"
    (let [json-body "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\",\"created\":1677652288,\"model\":\"llama3.2\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"The sky is blue\"},\"logprobs\":null,\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":12,\"total_tokens\":21}}"
          resp {:status 200 :body json-body}
          parsed (llama/parse-response resp)]
      (is (= (:response parsed) "The sky is blue"))
      (is (= (:tokens parsed) 21))
      (is (nil? (:error parsed)))))

  (testing "Error response parsing (e.g., 400 Bad Request)"
    (let [json-body "{\"error\":\"model not found\"}"
          resp {:status 400 :body json-body}
          parsed (llama/parse-response resp)]
      (is (= (:error parsed) true))
      (is (= (:reason parsed) "model not found"))
      (is (= (:backoff-ms parsed) 1000))))

  (testing "Error response parsing (e.g., generic non-JSON error)"
    (let [body "Internal Server Error"
          resp {:status 500 :body body}
          parsed (llama/parse-response resp)]
      (is (= (:error parsed) true))
      (is (= (:reason parsed) "HTTP Error: 500"))
      (is (= (:backoff-ms parsed) 1000)))))

(deftest parse-exception-test
  (testing "Exception parsing (e.g. network failure)"
    (let [parsed (llama/parse-exception (ex-info "Network error" {:cause :timeout}))]
      (is (= (:error parsed) true))
      (is (= (:reason parsed) "Network error"))
      (is (= (:backoff-ms parsed) 5000)))))
