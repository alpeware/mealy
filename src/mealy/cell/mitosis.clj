;; Copyright (c) Alpeware LLC. All rights reserved.

(ns mealy.cell.mitosis
  "Pure functions to evaluate complexity and perform state mitosis."
  (:require [mealy.cell.core :as cell]))

(defn threshold-reached?
  "Evaluates whether a cell's state complexity has breached the given threshold parameters.
  Supported threshold keys: :max-memory-keys, :max-observations."
  [state thresholds]
  (let [memory-count (count (:memory state))
        obs-count (count (:observations state))
        max-mem (:max-memory-keys thresholds)
        max-obs (:max-observations thresholds)]
    (or (and max-mem (> memory-count max-mem))
        (and max-obs (> obs-count max-obs))
        false)))

(defn divide
  "Partitions a parent cell's state into a new parent and child genesis state.
  Takes the original parent state, a set of partition-keys to move to the child's memory,
  and the child's aim.
  The child inherits the parent's SCI context (forked) and policies.
  Returns a map containing {:parent new-parent-state :child child-genesis-state}."
  [parent partition-keys child-aim]
  (let [parent-mem (:memory parent)
        child-mem (select-keys parent-mem partition-keys)
        new-parent-mem (apply dissoc parent-mem partition-keys)
        parent-sci-ctx (:sci-ctx parent)
        child (-> (cell/make-cell child-aim child-mem parent-sci-ctx)
                  ;; Inherit parent policies
                  (assoc :policies (:policies parent [])))
        new-parent (assoc parent :memory new-parent-mem)]
    {:parent new-parent
     :child child}))
