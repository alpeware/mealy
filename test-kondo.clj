(ns test-kondo
  (:require [clojure.test.check.clojure-test :refer [defspec]]))

(defspec ^{:doc "hello"} my-spec 100 true)
