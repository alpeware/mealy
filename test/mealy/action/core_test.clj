(ns mealy.action.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [mealy.action.core :as action]))

(defmethod action/execute :mock-action
  [_ env]
  (:mock-result env))

(deftest test-execute-multimethod
  (testing "execute dispatches based on the :type of the action"
    (let [action {:type :mock-action}
          env {:mock-result :success}]
      (is (= :success (action/execute action env))))))
