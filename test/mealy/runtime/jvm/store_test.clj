(ns mealy.runtime.jvm.store-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mealy.action.core]
            [mealy.runtime.jvm.store :as store]
            [mealy.runtime.protocols :as p]))

(defn- temp-dir []
  (let [dir (java.nio.file.Files/createTempDirectory "mealy-store-test" (into-array java.nio.file.attribute.FileAttribute []))]
    (.toFile dir)))

(defn- delete-dir [dir]
  (doseq [f (reverse (file-seq dir))]
    (.delete f)))

(deftest jvm-event-store-test
  (testing "JVMEventStore implementation of EventStore protocol"
    (let [dir (temp-dir)
          store (store/->JVMEventStore {:dir-path (.getAbsolutePath dir)})
          cell-id :test-cell]
      (try
        ;; Test put and get
        (p/put store cell-id [:test-event {:data 1}])
        (p/put store cell-id [:test-event {:data 2}])
        (is (= [[:test-event {:data 1}] [:test-event {:data 2}]]
               (p/get store cell-id)))

        ;; Test snapshot and load
        (p/snapshot store cell-id {:state {:phase :idle} :event-count 2})
        (is (= {:state {:phase :idle} :event-count 2}
               (p/load store cell-id)))

        ;; Verify sanitize logic for eval-success rich types
        (p/put store cell-id [:observation {:type :eval-success :result {:some-key (Object.)}}])
        (let [events (p/get store cell-id)
              last-event (last events)]
          (is (= :observation (first last-event)))
          (is (string? (:result (second last-event)))))
        (finally
          (delete-dir dir))))))

(deftest restore-cell-test
  (testing "restore-cell bootloader crash recovery"
    (let [dir (temp-dir)
          store (store/->JVMEventStore {:dir-path (.getAbsolutePath dir)})
          cell-id :recovery-cell
          initial-state {:phase :idle :memory {} :observations [] :handlers {}}]
      (try
        ;; Take a snapshot at event count 1
        (p/snapshot store cell-id {:state (assoc initial-state :phase :evaluating)
                                   :event-count 1})

        ;; Append events: one before the snapshot (should be skipped), one after (should be processed)
        ;; Note: In reality, events exist first, but we simulate the timeline via event count
        (p/put store cell-id [:tick {}]) ; Event 1 (skipped by drop)
        (p/put store cell-id [:observation {:type :tick}]) ; Event 2 (processed by reducer)

        (let [recovered-state (store/restore-cell store cell-id initial-state)]
          ;; Based on handle-observation, starting from :evaluating, an :observation transitions to :evaluating (no change) and adds it
          (is (= :evaluating (:phase recovered-state)))
          (is (= [{:type :tick}] (:observations recovered-state))))

        ;; Test rehydration of active policies
        (p/snapshot store cell-id {:state (assoc initial-state
                                                 :phase :idle
                                                 :memory {:active-policies ["(require '[mealy.action.core :as a])\n(defmethod a/execute :test-recovery [_ _] :recovered)"]})
                                   :event-count 0})
        ;; Clear previous log
        (io/delete-file (io/file dir "events-recovery-cell.log") true)

        (let [recovered-state (store/restore-cell store cell-id initial-state)]
          (is (= :idle (:phase recovered-state)))
          (is (some? (get-method mealy.action.core/execute :test-recovery))))

        (finally
          (remove-method mealy.action.core/execute :test-recovery)
          (delete-dir dir))))))
