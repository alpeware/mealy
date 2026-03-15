(ns mealy.test-runner
  "Test runner for Mealy"
  (:require [clojure.test :as t]
            [mealy.cell.core-test]
            [mealy.cell.mitosis-test]
            [mealy.cell.reducer-test]
            [mealy.core]))

(defn -main
  "Main entry point for running tests"
  [& _args]
  (let [results (t/run-all-tests #"mealy.*")]
    (System/exit (+ (:fail results) (:error results)))))
