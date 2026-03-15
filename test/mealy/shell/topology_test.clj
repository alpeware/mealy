;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.shell.topology-test
  "Tests for mealy.shell.topology"
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.cell.core :as cell]
            [mealy.shell.topology :as topology]))

(defspec ^{:doc "Generative invariant: The topology manager forwards non-replicate commands to upstream-chan."}
  test-topology-forwarding-invariant 50
  (prop/for-all [cmds (gen/vector (gen/map gen/keyword gen/any) 0 20)]
                (let [filtered-cmds (remove #(= (:type %) :replicate) cmds)
                      parent-in-chan (async/chan 100)
                      cmd-chan (async/chan 100)
                      upstream-chan (async/chan 100)
                      spawn-fn (fn [_ _ _] nil)]
                  (topology/manage-topology spawn-fn parent-in-chan cmd-chan upstream-chan)
                  (doseq [cmd filtered-cmds]
                    (async/>!! cmd-chan cmd))

                  ;; We close cmd-chan, which causes the topology loop to exit.
                  ;; Since it no longer closes upstream-chan, we must manually close it here to let the loop below terminate.
                  (async/close! cmd-chan)

                  ;; We need to read the forwarded messages. If we close upstream-chan immediately,
                  ;; we might miss the messages that `manage-topology` is still trying to put on it!
                  ;; Let's read them out first. Since we know how many we expect:
                  (let [expected-count (count filtered-cmds)
                        acc (loop [n expected-count
                                   results []]
                              (if (pos? n)
                                (let [timeout (async/timeout 500)
                                      [cmd port] (async/alts!! [upstream-chan timeout])]
                                  (if (= port timeout)
                                    results
                                    (recur (dec n) (conj results cmd))))
                                results))]
                    (async/close! upstream-chan)
                    (= acc (vec filtered-cmds))))))

(deftest test-topology-replicate
  (testing "Topology manager binds replicated children correctly"
    (let [parent-in-chan (async/chan 10)
          cmd-chan (async/chan 10)
          upstream-chan (async/chan 10)
          child-state (cell/make-cell "Child" {})

          ;; A mock start-shell that simply sends a specific message to its out-chan
          ;; when started, so we can verify the wiring.
          mock-start-shell (fn [_state _in-chan out-chan]
                             (async/go
                               (async/>! out-chan [:observation {:from-child "Child"}])
                               (async/close! out-chan)))]

      (topology/manage-topology mock-start-shell parent-in-chan cmd-chan upstream-chan)

      ;; Send replicate command
      (async/>!! cmd-chan {:type :replicate :child-state child-state})

      ;; Wait for the message on parent-in-chan with a timeout to avoid hanging
      (let [timeout (async/timeout 1000)
            [val port] (async/alts!! [parent-in-chan timeout])]
        (is (= parent-in-chan port) "Expected message on parent-in-chan, but timed out")
        (is (= [:observation {:from-child "Child"}] val)))

      ;; Close channels to clean up
      (async/close! cmd-chan)
      (async/close! upstream-chan))))
