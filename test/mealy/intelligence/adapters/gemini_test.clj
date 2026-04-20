(ns mealy.intelligence.adapters.gemini-test
  "Tests for the Gemini Provider Adapter"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.intelligence.adapters.gemini :as gemini]))

#_{:clj-kondo/ignore [:missing-docstring]}
(defspec build-request-test
  100
  (prop/for-all [api-key gen/string-alphanumeric
                 model gen/string-alphanumeric
                 system-prompt gen/string-alphanumeric
                 user-prompt gen/string-alphanumeric]
                (let [messages [{:role "system" :content system-prompt}
                                {:role "user" :content user-prompt}]
                      req (gemini/build-request api-key model messages)]
                  (and
                   (= (:method req) :post)
                   (= (:url req) (str "https://generativelanguage.googleapis.com/v1beta/models/" model ":generateContent?key=" api-key))
                   (= (get-in req [:headers "Content-Type"]) "application/json")
                   (= (:body req) (str "{\"contents\":[{\"parts\":[{\"text\":\"" user-prompt "\"}]}],\"systemInstruction\":{\"parts\":[{\"text\":\"" system-prompt "\"}]}}"))))))

(deftest parse-response-test
  (testing "Successful response parsing (pre-parsed EDN body)"
    (let [body {:candidates [{:content {:parts [{:text "Hello there!"}]}}]
                :usageMetadata {:totalTokenCount 15}}
          resp {:status 200 :body body}
          parsed (gemini/parse-response resp)]
      (is (= (:response parsed) "Hello there!"))
      (is (= (:tokens parsed) 15))
      (is (nil? (:error parsed)))))

  (testing "Successful response parsing (legacy JSON string body)"
    (let [json-body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello there!\"}]}}],\"usageMetadata\":{\"totalTokenCount\":15}}"
          resp {:status 200 :body json-body}
          parsed (gemini/parse-response resp)]
      (is (= (:response parsed) "Hello there!"))
      (is (= (:tokens parsed) 15))
      (is (nil? (:error parsed)))))

  (testing "Thinking model response with thought part filtered out"
    (let [body {:candidates [{:content {:parts [{:text "" :thought true}
                                                {:text "The actual response text."}]}
                              :finishReason "STOP"}]
                :usageMetadata {:totalTokenCount 170}}
          resp {:status 200 :body body}
          parsed (gemini/parse-response resp)]
      (is (= "The actual response text." (:response parsed)))
      (is (= 170 (:tokens parsed)))
      (is (nil? (:error parsed)))))

  (testing "Error response parsing (rate limit, pre-parsed)"
    (let [body {:error {:code 429 :message "Resource has been exhausted (e.g. check quota)."}}
          resp {:status 429 :body body}
          parsed (gemini/parse-response resp)]
      (is (= (:error parsed) true))
      (is (= (:reason parsed) "Resource has been exhausted (e.g. check quota)."))
      ;; 60 seconds backoff for 429
      (is (= (:backoff-ms parsed) 60000))))

  (testing "Error response parsing (generic 400, pre-parsed)"
    (let [body {:error {:code 400 :message "Bad Request"}}
          resp {:status 400 :body body}
          parsed (gemini/parse-response resp)]
      (is (= (:error parsed) true))
      (is (= (:reason parsed) "Bad Request"))
      (is (= (:backoff-ms parsed) 1000))))

  (testing "Namespace is pure"
    (is (= #{'build-request 'parse-response}
           (set (keys (ns-publics 'mealy.intelligence.adapters.gemini)))))))
