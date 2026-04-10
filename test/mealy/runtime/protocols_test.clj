(ns mealy.runtime.protocols-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [mealy.runtime.protocols :as p])
  (:refer-clojure :exclude [get load]))

(deftype MemoryEventStore [state]
  p/EventStore
  (snapshot [_ id data]
    (swap! state assoc-in [:snapshots id] data))
  (put [_ id event]
    (swap! state update-in [:events id] (fnil conj []) event))
  (load [_ id]
    (clojure.core/get-in @state [:snapshots id]))
  (get [_ id]
    (clojure.core/get-in @state [:events id] [])))

(defrecord MemoryEventBus [state]
  p/EventBus
  (register [_ id]
    (swap! state update :registered (fnil conj #{}) id))
  (subscribe [_ id topic handler]
    (swap! state assoc-in [:subscriptions topic id] handler))
  (publish [_ topic event]
    (let [subs (get-in @state [:subscriptions topic])]
      (doseq [[_ handler] subs]
        (handler event))))
  (query [_ topic]
    (get-in @state [:subscriptions topic] {})))

(deftest event-store-test
  (testing "MemoryEventStore implementation of EventStore protocol"
    (let [store (->MemoryEventStore (atom {}))]
      (p/put store :cell-1 {:type :test-event :data 1})
      (p/put store :cell-1 {:type :test-event :data 2})
      (is (= [{:type :test-event :data 1} {:type :test-event :data 2}]
             (p/get store :cell-1)))

      (p/snapshot store :cell-1 {:state :snapped})
      (is (= {:state :snapped} (p/load store :cell-1))))))

(deftest event-bus-test
  (testing "MemoryEventBus implementation of EventBus protocol"
    (let [bus (->MemoryEventBus (atom {}))
          received (atom [])]
      (p/register bus :cell-1)
      (is (contains? (:registered @(:state bus)) :cell-1))

      (p/subscribe bus :cell-1 :topic-a #(swap! received conj %))
      (is (= 1 (count (p/query bus :topic-a))))

      (p/publish bus :topic-a {:event 1})
      (p/publish bus :topic-b {:event 2}) ; should not be received

      (is (= [{:event 1}] @received)))))

(defspec event-store-put-get-invariant 100
  (prop/for-all [id gen/keyword
                 events (gen/vector gen/any)]
                (let [store (->MemoryEventStore (atom {}))]
                  (doseq [e events]
                    (p/put store id e))
                  (= events (p/get store id)))))

(defspec event-store-snapshot-load-invariant 100
  (prop/for-all [id gen/keyword
                 data gen/any]
                (let [store (->MemoryEventStore (atom {}))]
                  (p/snapshot store id data)
                  (= data (p/load store id)))))
