;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.shell.core-test
  "Tests for mealy.shell.core"
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.core :as cell]
            [mealy.shell.core :as shell]
            [taoensso.nippy :as nippy]))

(defspec ^{:doc "Generative invariant: The execution shell processes events sequentially and stops when in-chan closes."}
  test-shell-processing-invariant 50
  (prop/for-all [events (gen/vector (gen/tuple gen/keyword gen/any) 0 20)
                 aim gen/string-alphanumeric]
                (let [initial-state (cell/make-cell aim {})
                      in-chan (async/chan 100)
                      out-chan (async/chan 100)
                      temp-file (java.io.File/createTempFile "events" ".log")
                      log-path (.getAbsolutePath temp-file)

          ;; Start the shell
                      _ (shell/start-shell initial-state in-chan out-chan {:event-log-path log-path})

          ;; Send all events
                      _ (doseq [e events]
                          (async/>!! in-chan e))

          ;; Close in-chan to signal completion
                      _ (async/close! in-chan)]

      ;; We expect the shell loop to finish, which means it will process all events.
      ;; The out-chan won't get any commands for our generic events based on current reducer logic,
      ;; but we can drain it anyway just in case.
                  (loop [acc []]
                    (if-let [cmd (async/<!! out-chan)]
                      (recur (conj acc cmd))
                      (and (vector? acc)
                           ;; If we closed in-chan, the shell should close out-chan
                           (nil? (async/<!! out-chan))
                           (do (.delete temp-file) true)))))))

(deftest test-shell-processing
  (testing "Shell processes valid events and closes out-chan when in-chan closes"
    (let [initial-state (cell/make-cell "Aim" {})
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          temp-file (java.io.File/createTempFile "events" ".log")
          log-path (.getAbsolutePath temp-file)]
      (shell/start-shell initial-state in-chan out-chan {:event-log-path log-path})

      ;; Send an observation
      (async/>!! in-chan [:observation {:temp 98.6}])

      ;; Close the input channel
      (async/close! in-chan)

      ;; We expect the command from the phase transition
      (let [cmd (async/<!! out-chan)]
        (is (not (nil? cmd)) "Should yield a command for observation in idle phase")
        (is (= :llm-request (:type cmd))))

      ;; Then the out-chan should be closed
      (is (nil? (async/<!! out-chan)) "out-chan should be closed when in-chan is closed")
      (.delete temp-file))))

(deftest test-shell-event-logging
  (testing "Shell appends events to the specified event log before processing"
    (let [initial-state (cell/make-cell "Aim" {})
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          temp-file (java.io.File/createTempFile "events" ".log")
          log-path (.getAbsolutePath temp-file)]
      (shell/start-shell initial-state in-chan out-chan {:event-log-path log-path})

      (async/>!! in-chan [:observation {:temp 98.6}])
      (async/>!! in-chan [:observation {:temp 99.1}])
      (async/close! in-chan)
      (async/<!! out-chan) ; drain
      (async/<!! out-chan) ; drain

      (let [lines (with-open [r (io/reader log-path)]
                    (doall (line-seq r)))]
        (is (= 2 (count lines)))
        (is (= "[:observation {:temp 98.6}]" (first lines)))
        (is (= "[:observation {:temp 99.1}]" (second lines))))
      (.delete temp-file))))

(deftest test-shell-state-snapshots
  (testing "Shell periodically serializes the state map to a Nippy snapshot"
    (let [initial-state (cell/make-cell "Aim" {})
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          temp-log (java.io.File/createTempFile "events" ".log")
          temp-snap (java.io.File/createTempFile "snapshot" ".nippy")
          log-path (.getAbsolutePath temp-log)
          snap-path (.getAbsolutePath temp-snap)]

      (.delete temp-snap)

      (shell/start-shell initial-state in-chan out-chan
                         {:event-log-path log-path
                          :snapshot-path snap-path
                          :snapshot-interval 2})

      ;; Send exactly snapshot-interval events to trigger one snapshot write
      (async/>!! in-chan [:observation {:temp 98.6}])
      ;; Wait to ensure the go-loop processes the first event
      (async/<!! (async/timeout 50))

      (async/>!! in-chan [:observation {:temp 99.1}])

      ;; Give shell time to process the second event and write the snapshot
      (async/<!! (async/timeout 200))

      (async/close! in-chan)

      (loop []
        (when-let [_ (async/<!! out-chan)]
          (recur)))

      ;; Now check if a valid Nippy snapshot was created
      (let [snap-file (io/file snap-path)]
        (is (.exists snap-file) "Snapshot file should exist")
        (is (> (.length snap-file) 0) "Snapshot file should not be empty")

        ;; Verify the state can be thawed and represents the final state
        ;; The reducer adds both observations
        (let [thawed-snapshot (nippy/thaw-from-file snap-path)
              thawed-state (:state thawed-snapshot)
              event-count (:event-count thawed-snapshot)]
          (is (= 2 event-count))
          (is (= "Aim" (:aim thawed-state)))
          (is (= 2 (count (:observations thawed-state))))
          (is (= [{:temp 98.6} {:temp 99.1}] (:observations thawed-state)))))

      (.delete temp-log)
      (.delete temp-snap))))

(deftest test-restore-cell
  (testing "restore-cell recovers state from snapshot and event log"
    (let [initial-state (cell/make-cell "Aim" {})
          temp-log (java.io.File/createTempFile "events" ".log")
          temp-snap (java.io.File/createTempFile "snapshot" ".nippy")
          log-path (.getAbsolutePath temp-log)
          snap-path (.getAbsolutePath temp-snap)]

      ;; Step 1: Create a base snapshot at event count 1
      (let [state-at-1 (-> initial-state
                           (assoc :phase :evaluating)
                           (assoc :observations [{:temp 98.6}]))]
        (nippy/freeze-to-file snap-path {:state state-at-1 :event-count 1}))

      ;; Step 2: Write events to the log
      ;; The first event was already captured in the snapshot.
      (spit log-path (str (pr-str [:observation {:temp 98.6}]) "\n") :append true)
      ;; The next two events occurred AFTER the snapshot was taken.
      (spit log-path (str (pr-str [:observation {:temp 99.1}]) "\n") :append true)
      (spit log-path (str (pr-str [:evaluation-error {:reason "timeout"}]) "\n") :append true)

      ;; Step 3: Call restore-cell
      (let [restored-state (shell/restore-cell initial-state
                                               {:snapshot-path snap-path
                                                :event-log-path log-path})]

        ;; The state should have the second observation and the last error.
        (is (= "Aim" (:aim restored-state)))
        (is (= :idle (:phase restored-state)))
        (is (= "timeout" (:last-error restored-state)))
        (is (= [{:temp 98.6} {:temp 99.1}] (:observations restored-state))))

      (.delete temp-log)
      (.delete temp-snap)))

  (testing "restore-cell works with missing snapshot (replays from beginning)"
    (let [initial-state (cell/make-cell "Aim" {})
          temp-log (java.io.File/createTempFile "events" ".log")
          temp-snap (java.io.File/createTempFile "snapshot-missing" ".nippy")
          log-path (.getAbsolutePath temp-log)
          snap-path (.getAbsolutePath temp-snap)]

      (spit log-path (str (pr-str [:observation {:temp 98.6}]) "\n") :append true)
      (spit log-path (str (pr-str [:observation {:temp 99.1}]) "\n") :append true)

      (.delete temp-snap) ; ensure it doesn't exist

      (let [restored-state (shell/restore-cell initial-state
                                               {:snapshot-path snap-path
                                                :event-log-path log-path})]

        (is (= [{:temp 98.6} {:temp 99.1}] (:observations restored-state))))

      (.delete temp-log)))

  (testing "restore-cell works with missing event log"
    (let [initial-state (cell/make-cell "Aim" {})
          temp-log (java.io.File/createTempFile "events-missing" ".log")
          temp-snap (java.io.File/createTempFile "snapshot" ".nippy")
          log-path (.getAbsolutePath temp-log)
          snap-path (.getAbsolutePath temp-snap)]

      (let [state-at-1 (-> initial-state
                           (assoc :phase :evaluating)
                           (assoc :observations [{:temp 98.6}]))]
        (nippy/freeze-to-file snap-path {:state state-at-1 :event-count 1}))

      (.delete temp-log) ; ensure it doesn't exist

      (let [restored-state (shell/restore-cell initial-state
                                               {:snapshot-path snap-path
                                                :event-log-path log-path})]

        (is (= [{:temp 98.6}] (:observations restored-state))))

      (.delete temp-snap))))
