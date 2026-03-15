;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.shell.topology
  "Dynamic channel-wiring logic that manages replicating cells and binding children to parents."
  (:require [clojure.core.async :as async :refer [go-loop <! >!]]))

(defn manage-topology
  "Listens to `cmd-chan` for commands.
  If a command is `{:type :replicate :child-state state}`, it dynamically creates
  child channels, calls the provided `spawn-fn` (e.g., `mealy.shell.core/start-shell`)
  to start the child shell, and recursively invokes `manage-topology` to bind the
  child to the parent.
  For other commands, it forwards them to `upstream-chan`."
  [spawn-fn parent-in-chan cmd-chan upstream-chan]
  (go-loop []
    (if-let [cmd (<! cmd-chan)]
      (do
        (if (and (map? cmd) (= (:type cmd) :replicate))
          (let [child-state (:child-state cmd)
                child-in-chan (async/chan 100)
                child-out-chan (async/chan 100)]
            ;; Start the child shell using the provided spawn-fn
            (spawn-fn child-state child-in-chan child-out-chan)

            ;; Manage the child's topology recursively.
            (manage-topology spawn-fn child-in-chan child-out-chan parent-in-chan))

          ;; For other commands, forward them to the provided upstream-chan.
          (>! upstream-chan cmd))
        (recur))

      ;; When cmd-chan closes, simply exit the loop.
      ;; Do not close upstream-chan because it might be a shared parent channel.
      nil)))
