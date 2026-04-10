(ns mealy.runtime.jvm.core-test
  "Tests for mealy.runtime.jvm.core"
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]
            [mealy.cell.core :as cell]
            [mealy.cell.reducer :as reducer]
            [mealy.runtime.jvm.bus :as bus]
            [mealy.runtime.jvm.core :as core]
            [mealy.runtime.jvm.store :as store]
            [mealy.runtime.protocols :as p]))

(defn- temp-dir []
  (let [dir (java.nio.file.Files/createTempDirectory "mealy-core-test" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile dir)))

(defn- delete-dir [dir]
  (doseq [f (reverse (file-seq dir))]
    (.delete f)))

(deftest test-start-node
  (testing "start-node orchestrates store, bus, and worker pool"
    (let [dir (temp-dir)
          store-opts {:dir-path (.getAbsolutePath dir)}
          event-store (store/->JVMEventStore store-opts)
          event-bus (bus/make-bus)
          initial-state (cell/make-cell "Test Aim" {})
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          execution-chan (async/chan 10)]
      (try
        ;; Register a test action
        (defmethod action/execute :test-action
          [action env]
          (async/put! execution-chan {:action action :env env}))

        (let [opts {:workers 2
                    :snapshot-interval 2
                    :gateway-chan "mock-gateway"
                    :cell-in-chan "mock-cell-in"}
              node-map (core/start-node event-store event-bus :test-node initial-state in-chan out-chan opts)
              app-out-chan (:app-out-chan node-map)]

          (is (not (nil? (:node-loop node-map))))

          ;; Test that the worker pool processes actions correctly
          (async/>!! out-chan {:type :execute-action
                               :action {:type :test-action
                                        :payload "hello"}})
          (let [[result _] (async/alts!! [execution-chan (async/timeout 1000)])]
            (is (not (nil? result)))
            (is (= :test-action (-> result :action :type)))
            (is (= "hello" (-> result :action :payload)))
            (is (= "mock-gateway" (-> result :env :gateway-chan)))
            (is (= "mock-cell-in" (-> result :env :cell-in-chan))))

          ;; Test that sending an event persists it and routes commands
          (with-redefs [reducer/handle-event
                        (fn [state _event]
                          {:state state
                           :actions [{:type :execute-action :action {:type :test-action :payload "from-reducer"}}
                                     {:type :app-event :payload "ui-ready"}]})]
            (async/>!! in-chan [:observation {:temp 98.6}])

            ;; Check that it produced an app event
            (let [[cmd2 _] (async/alts!! [app-out-chan (async/timeout 1000)])]
              (is (not (nil? cmd2)))
              (is (= :app-event (:type cmd2)))
              (is (= "ui-ready" (:payload cmd2))))

            ;; Check that it executed the action via worker pool
            (let [[result2 _] (async/alts!! [execution-chan (async/timeout 1000)])]
              (is (not (nil? result2)))
              (is (= "from-reducer" (-> result2 :action :payload))))

            ;; Wait a bit for async file write
            (async/<!! (async/timeout 100))

            ;; Verify persistence to store
            (let [events (p/get event-store :test-node)]
              (is (= 1 (count events)))
              (is (= [:observation {:temp 98.6}] (first events)))))

          (async/close! in-chan))
        (finally
          (remove-method action/execute :test-action)
          (delete-dir dir))))))
