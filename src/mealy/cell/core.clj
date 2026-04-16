(ns mealy.cell.core
  "Core data structures and definitions for a Mealy Cell."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [sci.core :as sci]))

;; Keys that must be excluded from persistence/snapshots because they
;; contain live JVM objects (SCI contexts, compiled fns, etc.).
(def ^{:doc "Set of state keys that are non-serializable and must be excluded from snapshots."}
  transient-keys
  #{:sci-ctx})

(defn make-sci-ctx
  "Creates a fresh, isolated SCI context for a cell.
  Optionally copies bindings from a parent SCI context so a child cell
  inherits the parent's learned skills but can evolve independently."
  ([]
   (sci/init {:namespaces {'clojure.core.async {'put! a/put!}
                           'clojure.string {'join str/join
                                            'replace str/replace
                                            'includes? str/includes?
                                            'upper-case str/upper-case
                                            'split str/split
                                            'trim str/trim
                                            'blank? str/blank?}}}))
  ([parent-sci-ctx]
   ;; Fork: create a fresh context that starts with the same namespace state
   ;; as the parent.  SCI does not expose a native fork, so we create a new
   ;; context with the same namespace map.
   (let [ctx (sci/init {:namespaces {'clojure.core.async {'put! a/put!}
                                     'clojure.string {'join str/join
                                                      'replace str/replace
                                                      'includes? str/includes?
                                                      'upper-case str/upper-case
                                                      'split str/split
                                                      'trim str/trim
                                                      'blank? str/blank?}}})]
     ;; Copy all user-added vars from the parent into the child.
     ;; This is intentionally shallow — the child starts with the same
     ;; skill set but mutates independently.
     (doseq [[ns-sym ns-map] (:namespaces @(:env parent-sci-ctx))
             :when (not= ns-sym 'clojure.core.async)
             [var-sym sci-var] ns-map
             :when (instance? sci.lang.Var sci-var)]
       (swap! (:env ctx) assoc-in [:namespaces ns-sym var-sym] sci-var))
     ctx)))

(defn make-cell
  "Creates a new pure Cell state map with the given aim and memory.
  Observations are initialized as an empty vector.
  Policies are initialized as an empty vector of boundary strings.
  An isolated SCI context is stored under :sci-ctx (excluded from snapshots)."
  ([aim memory]
   (make-cell aim memory nil))
  ([aim memory parent-sci-ctx]
   {:aim aim
    :memory memory
    :observations []
    :policies []
    :phase :idle
    :bus-topics #{}
    :parent :anchor
    :sci-ctx (if parent-sci-ctx
               (make-sci-ctx parent-sci-ctx)
               (make-sci-ctx))}))

(defn sanitize-for-snapshot
  "Strips transient (non-serializable) keys from a cell state before persistence."
  [state]
  (apply dissoc state transient-keys))
