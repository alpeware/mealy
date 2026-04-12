(ns mealy.runtime.jvm.bus-test
  (:require [clojure.core.async :refer [>!! alts!! chan timeout]]
            [clojure.test :refer [deftest is testing]]
            [mealy.runtime.jvm.bus :as bus]
            [mealy.runtime.protocols :as p]))

(deftest jvm-event-bus-test
  (testing "JVM Event Bus implementation"
    (let [event-bus (bus/make-bus)
          ch1 (chan 10)
          ch2 (chan 10)]
      (p/register event-bus :topic-a)
      (p/register event-bus :topic-b)

      (is (= #{:topic-a :topic-b} (p/query event-bus)))
      (is (= {} (p/query event-bus :topic-a)))

      (p/subscribe event-bus :cell-1 :topic-a (fn [{:keys [event]}] (>!! ch1 event)))
      (p/subscribe event-bus :cell-2 :topic-a (fn [{:keys [event]}] (>!! ch2 event)))

      (is (= 2 (count (p/query event-bus :topic-a))))

      (p/publish event-bus :topic-a {:msg "hello"})

      (let [[v1 _] (alts!! [ch1 (timeout 100)])]
        (is (= {:msg "hello"} v1)))

      (let [[v2 _] (alts!! [ch2 (timeout 100)])]
        (is (= {:msg "hello"} v2)))

      (p/unsubscribe event-bus :topic-a :cell-1)
      (is (= 1 (count (p/query event-bus :topic-a))))
      (is (not (contains? (p/query event-bus :topic-a) :cell-1)))

      (p/publish event-bus :topic-a {:msg "world"})

      (let [[v1 _] (alts!! [ch1 (timeout 100)])]
        (is (nil? v1)))

      (let [[v2 _] (alts!! [ch2 (timeout 100)])]
        (is (= {:msg "world"} v2))))))
