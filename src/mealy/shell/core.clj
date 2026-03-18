;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.shell.core
  "The execution shell for Mealy cells. Adapts the pure Sans-IO core to core.async channels."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [mealy.action.core :as action]
            [mealy.cell.reducer :as reducer]
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

(defn- start-worker-pool
  "Starts a generic worker pool to drain `out-chan` and execute actions via `mealy.action.core/execute`."
  [out-chan opts]
  (let [num-workers (:workers opts 4)
        env (assoc opts :out-chan out-chan)]
    (dotimes [_ num-workers]
      (go-loop []
        (when-let [cmd (<! out-chan)]
          (when (= (:type cmd) :execute-action)
            (action/execute (:action cmd) env))
          (recur))))))

(defn restore-cell
  "Bootloader crash recovery routine.
  Reads the latest snapshot (if it exists) to obtain a base state and an event count `N`.
  Then reads `events.log`, skips the first `N` lines, and replays the remaining events
  through the pure `reducer/handle-event` to perfectly reconstruct the state.
  Returns the reconstructed state map."
  [initial-state opts]
  (let [log-path (:event-log-path opts "events.log")
        snapshot-path (:snapshot-path opts "snapshot.nippy")
        snap-file (io/file snapshot-path)
        log-file (io/file log-path)
        snapshot-exists? (.exists snap-file)
        {base-state :state
         event-count :event-count} (if snapshot-exists?
                                     (nippy/thaw-from-file snapshot-path)
                                     {:state initial-state :event-count 0})
        final-state (if (.exists log-file)
                      (with-open [r (io/reader log-file)]
                        (reduce (fn [s e]
                                  (:state (reducer/handle-event s e)))
                                base-state
                                (map edn/read-string (drop event-count (line-seq r)))))
                      base-state)
        active-policies (get-in final-state [:memory :active-policies] [])]
    (doseq [policy active-policies]
      (sci/eval-string* action/sci-ctx policy))
    final-state))

(defn start-shell
  "Spawns a go-loop that consumes events from `in-chan`, processes them
  through the pure `reducer/handle-event`, and routes any resulting commands
  to `out-chan`. When `in-chan` is closed, the loop terminates and closes `out-chan`.
  Options can provide :snapshot-interval and :snapshot-path to periodically serialize the state."
  ([initial-state in-chan out-chan]
   (start-shell initial-state in-chan out-chan {}))
  ([initial-state in-chan out-chan opts]
   (let [log-path (:event-log-path opts "events.log")
         snapshot-interval (:snapshot-interval opts)
         snapshot-path (:snapshot-path opts "snapshot.nippy")]

     (start-worker-pool out-chan opts)

     (go-loop [state initial-state
               event-count 0]
       (if-let [event (<! in-chan)]
         (let [_ (<! (async/thread (spit log-path (str (pr-str (sanitize-event event)) "\n") :append true)))
               {:keys [state commands]} (reducer/handle-event state event)
               new-count (inc event-count)]

           (when (and snapshot-interval
                      (zero? (mod new-count snapshot-interval)))
             (<! (async/thread (nippy/freeze-to-file snapshot-path {:state state :event-count new-count}))))

           (doseq [cmd commands]
             (>! out-chan cmd))
           (recur state new-count))
         (async/close! out-chan))))))
