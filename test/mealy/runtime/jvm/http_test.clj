(ns mealy.runtime.jvm.http-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as hc]
            [mealy.runtime.jvm.bus :as bus]
            [mealy.runtime.jvm.core :as core]
            [mealy.runtime.jvm.store :as store]))

(defn- temp-dir []
  (let [dir (java.nio.file.Files/createTempDirectory "mealy-http-test" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile dir)))

(deftest intercept-http-request-test
  (testing "worker pool intercepts :http-request and auto-parses JSON body"
    (let [dir (temp-dir)
          store-opts {:dir-path (.getAbsolutePath dir)}
          event-store (store/->JVMEventStore store-opts)
          event-bus (bus/make-bus)
          out-chan (async/chan 10)
          cell-in-chan (async/chan 10)
          opts {:workers 1 :cell-in-chan cell-in-chan}]
      (with-redefs [hc/request (fn [req]
                                 (is (= "http://example.com" (:url req)))
                                 ;; Return a completed CompletableFuture with JSON content-type
                                 (java.util.concurrent.CompletableFuture/completedFuture
                                  {:status 200
                                   :headers {"content-type" "application/json; charset=utf-8"}
                                   :body "{\"result\":\"ok\",\"count\":42}"}))]
        (let [_node (core/start-node event-store event-bus :test-node {} (async/chan) out-chan opts)]
          (async/>!! out-chan {:type :http-request
                               :req {:url "http://example.com" :method :get}
                               :callback-event :http-result})
          (let [[val port] (async/alts!! [cell-in-chan (async/timeout 1000)])]
            (is (= port cell-in-chan))
            ;; Body should be auto-parsed from JSON string into EDN map
            (is (= [:http-result {:response {:status 200 :body {:result "ok" :count 42}}}] val)))))))

  (testing "worker pool passes non-JSON body through as-is"
    (let [dir (temp-dir)
          store-opts {:dir-path (.getAbsolutePath dir)}
          event-store (store/->JVMEventStore store-opts)
          event-bus (bus/make-bus)
          out-chan (async/chan 10)
          cell-in-chan (async/chan 10)
          opts {:workers 1 :cell-in-chan cell-in-chan}]
      (with-redefs [hc/request (fn [req]
                                 (is (= "http://example.com/text" (:url req)))
                                 (java.util.concurrent.CompletableFuture/completedFuture
                                  {:status 200
                                   :headers {"content-type" "text/plain"}
                                   :body "hello world"}))]
        (let [_node (core/start-node event-store event-bus :test-node-2 {} (async/chan) out-chan opts)]
          (async/>!! out-chan {:type :http-request
                               :req {:url "http://example.com/text" :method :get}
                               :callback-event :text-result})
          (let [[val port] (async/alts!! [cell-in-chan (async/timeout 1000)])]
            (is (= port cell-in-chan))
            ;; Non-JSON body should pass through as raw string
            (is (= [:text-result {:response {:status 200 :body "hello world"}}] val))))))))
