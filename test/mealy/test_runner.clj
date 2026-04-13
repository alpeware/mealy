(ns mealy.test-runner
  (:require [clojure.test :refer [run-all-tests]]
            [mealy.action.core-test]
            [mealy.cell.core-test]
            [mealy.cell.mitosis-test]
            [mealy.cell.reducer-test]
            [mealy.intelligence.adapters.gemini-test]
            [mealy.intelligence.adapters.llama-test]
            [mealy.intelligence.integration-test]
            [mealy.intelligence.provider-test]
            [mealy.intelligence.router-test]
            [mealy.ooda.prompt-test]
            [mealy.runtime.jvm.bus-test]
            [mealy.runtime.jvm.core-test]
            [mealy.runtime.jvm.store-test]
            [mealy.runtime.jvm.http-test]
            [mealy.runtime.protocols-test]
            [mealy.sociocracy-integration-test]))

(defn -main
  "Run all tests."
  []
  (let [results (run-all-tests)]
    (System/exit (+ (:fail results) (:error results)))))
