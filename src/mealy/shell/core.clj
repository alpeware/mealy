;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.shell.core
  "The execution shell for Mealy cells. Adapts the pure Sans-IO core to core.async channels."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]
            [mealy.cell.reducer :as reducer]))

(defn start-shell
  "Spawns a go-loop that consumes events from `in-chan`, processes them
  through the pure `reducer/handle-event`, and routes any resulting commands
  to `out-chan`. When `in-chan` is closed, the loop terminates and closes `out-chan`."
  [initial-state in-chan out-chan]
  (go-loop [state initial-state]
    (if-let [event (<! in-chan)]
      (let [{:keys [state commands]} (reducer/handle-event state event)]
        (doseq [cmd commands]
          (>! out-chan cmd))
        (recur state))
      (async/close! out-chan))))
