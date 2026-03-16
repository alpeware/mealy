(ns mealy.test-runner
  "Test runner for Mealy"
  (:require [clojure.test :as t]
            [mealy.cell.core-test]
            [mealy.cell.mitosis-test]
            [mealy.cell.reducer-test]
            [mealy.core]
            [mealy.intelligence.provider-test]
            [mealy.intelligence.router-test]
            [mealy.ooda.actuator-test]
            [mealy.ooda.prompt-test]
            [mealy.shell.core-test]
            [mealy.shell.topology-test]))

(defn -main
  "Main entry point for running tests"
  [& _args]
  (let [results (t/run-all-tests #"mealy.*")]
    (System/exit (+ (:fail results) (:error results)))))
