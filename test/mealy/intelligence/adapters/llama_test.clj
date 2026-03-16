(ns mealy.intelligence.adapters.llama-test
  "Tests for the Llama Provider Adapter"
  (:require [clojure.test :refer [deftest is testing]]
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
                (let [req (llama/build-request url model prompt)]
                  (and
                   (= (:method req) :post)
                   (= (:url req) (str url "/api/generate"))
                   (= (get-in req [:headers "Content-Type"]) "application/json")
                   (= (:body req) (str "{\"model\":\"" model "\",\"prompt\":\"" prompt "\",\"stream\":false}"))))))

(deftest parse-response-test
  (testing "Successful response parsing"
    (let [json-body "{\"model\":\"llama3.2\",\"created_at\":\"2023-08-04T19:22:45.499127Z\",\"response\":\"The sky is blue\",\"done\":true,\"context\":[1,2,3],\"total_duration\":10706818083,\"load_duration\":6338219291,\"prompt_eval_count\":26,\"prompt_eval_duration\":130079000,\"eval_count\":259,\"eval_duration\":4232710000}"
          resp {:status 200 :body json-body}
          parsed (llama/parse-response resp)]
      (is (= (:response parsed) "The sky is blue"))
      (is (= (:tokens parsed) 259))
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
