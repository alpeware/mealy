(ns mealy.cell.core
  "Core data structures and definitions for a Mealy Cell.")

(defn make-cell
  "Creates a new pure Cell state map with the given aim and memory.
  Observations are initialized as an empty vector."
  [aim memory]
  {:aim aim
   :memory memory
   :observations []
   :phase :idle
   :subscriptions #{}
   :handlers {}
   :actions {}
   :parent nil
   :children #{}})
