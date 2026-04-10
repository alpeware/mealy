(ns mealy.runtime.jvm.store
  "JVM implementation of the EventStore protocol using taoensso.nippy and append-only event logs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [mealy.action.core :as action]
            [mealy.cell.reducer :as reducer]
            [mealy.runtime.protocols :as p]
            [sci.core :as sci]
            [taoensso.nippy :as nippy]))

(defn sanitize-event
  "Sanitizes an event for EDN serialization at the IO boundary.
  Converts rich types in `:eval-success` observations into strings."
  [[event-type event-data :as event]]
  (if (and (= event-type :observation)
           (= (:type event-data) :eval-success)
           (contains? event-data :result))
    [event-type (update event-data :result pr-str)]
    event))

(defn- snapshot-file [dir id]
  (io/file dir (str "snapshot-" (name id) ".nippy")))

(defn- event-log-file [dir id]
  (io/file dir (str "events-" (name id) ".log")))

(deftype JVMEventStore [opts]
  p/EventStore
  (snapshot [_ id data]
    (let [dir (:dir-path opts ".")
          f (snapshot-file dir id)]
      (nippy/freeze-to-file f data)))

  (put [_ id event]
    (let [dir (:dir-path opts ".")
          f (event-log-file dir id)
          sanitized (sanitize-event event)]
      (spit f (str (pr-str sanitized) "\n") :append true)))

  (load [_ id]
    (let [dir (:dir-path opts ".")
          f (snapshot-file dir id)]
      (if (.exists f)
        (nippy/thaw-from-file f)
        nil)))

  (get [_ id]
    (let [dir (:dir-path opts ".")
          f (event-log-file dir id)]
      (if (.exists f)
        (with-open [r (io/reader f)]
          (vec (map edn/read-string (line-seq r))))
        []))))

(defn restore-cell
  "Bootloader crash recovery routine.
  Reads the latest snapshot (if it exists via p/load) to obtain a base state and an event count `N`.
  Then reads the event log via p/get, skips the first `N` events, and replays the remaining events
  through the pure `reducer/handle-event` to perfectly reconstruct the state.
  Returns the reconstructed state map."
  [store id initial-state]
  (let [snapshot-data (p/load store id)
        {base-state :state
         event-count :event-count} (if snapshot-data
                                     snapshot-data
                                     {:state initial-state :event-count 0})
        all-events (p/get store id)
        new-events (drop event-count all-events)
        final-state (reduce (fn [s e]
                              (:state (reducer/handle-event s e)))
                            base-state
                            new-events)
        active-policies (clojure.core/get-in final-state [:memory :active-policies] [])]
    (doseq [policy active-policies]
      (sci/eval-string* action/sci-ctx policy))
    final-state))
