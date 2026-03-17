(ns mealy.test-runner
  "Test runner for Mealy"
  (:require [clojure.test :as t]
            [mealy.action.core-test]
            [mealy.cell.core-test]
            [mealy.cell.mitosis-test]
            [mealy.cell.reducer-test]
            [mealy.core]
            [mealy.intelligence.adapters.gemini-test]
            [mealy.intelligence.adapters.llama-test]
            [mealy.intelligence.integration-test]
            [mealy.intelligence.provider-test]
            [mealy.intelligence.router-test]
            [mealy.ooda.prompt-test]
            [mealy.shell.core-test]
            [mealy.shell.topology-test]))

(defn -main
  "Main entry point for running tests"
  [& _args]
  (let [results (t/run-all-tests #"mealy.*")]
    (System/exit (+ (:fail results) (:error results)))))
