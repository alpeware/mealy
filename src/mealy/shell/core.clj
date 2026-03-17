;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.shell.core
  "The execution shell for Mealy cells. Adapts the pure Sans-IO core to core.async channels."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [mealy.cell.reducer :as reducer]
            [taoensso.nippy :as nippy]))

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
     (go-loop [state initial-state
               event-count 0]
       (if-let [event (<! in-chan)]
         (let [_ (<! (async/thread (spit log-path (str (pr-str event) "\n") :append true)))
               {:keys [state commands]} (reducer/handle-event state event)
               new-count (inc event-count)]

           (when (and snapshot-interval
                      (zero? (mod new-count snapshot-interval)))
             (<! (async/thread (nippy/freeze-to-file snapshot-path state))))

           (doseq [cmd commands]
             (>! out-chan cmd))
           (recur state new-count))
         (async/close! out-chan))))))
