(ns dev.frontend
  "Mealy Dev Dashboard — ClojureScript SPA using Reagent.
   Provides a configuration form to seed a cell with an aim and adapters,
   then displays live state and event logs via polling.
   All API communication uses EDN."
  (:require [ajax.core :refer [GET POST]]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

;; ---------------------------------------------------------------------------
;; App state
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Top-level Reagent atom holding all dashboard UI state."}
  app-state
  (r/atom {:view :config ;; :config | :dashboard
           :cell-state nil
           :events []
           :error nil
           :aim "Learn to autonomously summarize incoming observations."
           :adapters [{:adapter-type :gemini
                       :model "gemini-2.5-flash"
                       :api-key ""
                       :url ""
                       :status :healthy
                       :budget 10000
                       :complexity :high}
                      {:adapter-type :llama
                       :model "llama3"
                       :api-key ""
                       :url "http://localhost:11434"
                       :status :healthy
                       :budget 50000
                       :complexity :medium}]
           :event-input "[:observation {:type :test :data \"hello\"}]"
           :polling? false}))

;; ---------------------------------------------------------------------------
;; API helpers
;; ---------------------------------------------------------------------------

(defn ^:private poll-state!
  "Fetches current cell state as EDN from the backend."
  []
  (GET "/api/state"
    {:response-format :text
     :handler (fn [resp]
                (swap! app-state assoc :cell-state
                       (reader/read-string resp)))
     :error-handler (fn [_] nil)}))

(defn ^:private poll-events!
  "Fetches all persisted events as EDN from the backend."
  []
  (GET "/api/events"
    {:response-format :text
     :handler (fn [resp]
                (swap! app-state assoc :events
                       (reader/read-string resp)))
     :error-handler (fn [_] nil)}))

(defn ^:private start-polling!
  "Begins a 2-second polling interval for state and events."
  []
  (when-not (:polling? @app-state)
    (swap! app-state assoc :polling? true)
    (js/setInterval (fn []
                      (poll-state!)
                      (poll-events!))
                    2000)))

(defn ^:private start-cell!
  "POSTs the aim and adapter configuration as EDN to the backend to start a cell."
  []
  (let [{:keys [aim adapters]} @app-state]
    (POST "/api/cell/start"
      {:body (pr-str {:aim aim :adapters adapters})
       :headers {"Content-Type" "application/edn"}
       :response-format :text
       :handler (fn [resp]
                  (let [parsed (reader/read-string resp)]
                    (if (:error parsed)
                      (swap! app-state assoc :error (:error parsed))
                      (do
                        (swap! app-state assoc
                               :view :dashboard
                               :error nil)
                        (poll-state!)
                        (poll-events!)
                        (start-polling!)))))
       :error-handler (fn [err]
                        (swap! app-state assoc :error (str "Failed to start cell: " (pr-str err))))})))

(defn ^:private send-event!
  "Sends raw EDN text from the input field directly to the cell."
  []
  (let [input (:event-input @app-state)]
    (when (seq input)
      (POST "/api/cell/event"
        {:body input
         :headers {"Content-Type" "application/edn"}
         :response-format :text
         :handler (fn [_]
                    (swap! app-state assoc :event-input "")
                    (poll-state!)
                    (poll-events!))
         :error-handler (fn [_] nil)}))))

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn ^:private adapter-card
  "Renders a single adapter configuration card with fields for model, API key/URL, budget and complexity."
  [idx adapter]
  (let [is-gemini (= (:adapter-type adapter) :gemini)]
    [:div.adapter-card
     [:div.adapter-header
      [:span.adapter-badge
       {:class (if is-gemini "badge-gemini" "badge-llama")}
       (if is-gemini "✦ Gemini" "🦙 Llama")]
      [:button.btn-remove
       {:on-click #(swap! app-state update :adapters
                          (fn [v] (into [] (concat (subvec v 0 idx) (subvec v (inc idx))))))}
       "×"]]
     [:div.adapter-fields
      [:div.field-row
       [:label "Model"]
       [:input {:type "text"
                :value (:model adapter)
                :on-change #(swap! app-state assoc-in [:adapters idx :model] (.. % -target -value))}]]
      (when is-gemini
        [:div.field-row
         [:label "API Key"]
         [:input {:type "password"
                  :value (:api-key adapter)
                  :placeholder "Enter Gemini API key..."
                  :on-change #(swap! app-state assoc-in [:adapters idx :api-key] (.. % -target -value))}]])
      (when-not is-gemini
        [:div.field-row
         [:label "URL"]
         [:input {:type "text"
                  :value (:url adapter)
                  :placeholder "http://localhost:11434"
                  :on-change #(swap! app-state assoc-in [:adapters idx :url] (.. % -target -value))}]])
      [:div.field-row-pair
       [:div.field-col
        [:label "Budget"]
        [:input {:type "number"
                 :value (:budget adapter)
                 :on-change #(swap! app-state assoc-in [:adapters idx :budget]
                                    (js/parseInt (.. % -target -value)))}]]
       [:div.field-col
        [:label "Complexity"]
        [:select {:value (name (:complexity adapter))
                  :on-change #(swap! app-state assoc-in [:adapters idx :complexity]
                                     (keyword (.. % -target -value)))}
         [:option {:value "low"} "Low"]
         [:option {:value "medium"} "Medium"]
         [:option {:value "high"} "High"]]]]]]))

(defn ^:private add-adapter-buttons
  "Renders buttons for adding new Gemini or Llama adapter configurations."
  []
  [:div.add-adapter-row
   [:button.btn-add.btn-gemini
    {:on-click #(swap! app-state update :adapters conj
                       {:adapter-type :gemini :model "gemini-2.5-flash" :api-key ""
                        :url "" :status :healthy :budget 10000 :complexity :high})}
    "+ Add Gemini"]
   [:button.btn-add.btn-llama
    {:on-click #(swap! app-state update :adapters conj
                       {:adapter-type :llama :model "llama3" :api-key ""
                        :url "http://localhost:11434" :status :healthy :budget 50000 :complexity :medium})}
    "+ Add Llama"]])

(defn ^:private config-view
  "Renders the initial configuration page with aim and adapter forms."
  []
  [:div.config-container
   [:div.config-card
    [:div.card-header
     [:h1 "🧠 Mealy Cell Dashboard"]
     [:p.subtitle "Configure and launch a live Mealy cell"]]
    [:div.section
     [:h2 "Cell Aim"]
     [:textarea.aim-input
      {:value (:aim @app-state)
       :rows 3
       :placeholder "Describe the cell's objective..."
       :on-change #(swap! app-state assoc :aim (.. % -target -value))}]]
    [:div.section
     [:h2 "LLM Adapters"]
     (doall
      (map-indexed
       (fn [idx adapter]
         ^{:key idx} [adapter-card idx adapter])
       (:adapters @app-state)))
     [add-adapter-buttons]]
    (when (:error @app-state)
      [:div.error-banner (:error @app-state)])
    [:button.btn-start
     {:on-click start-cell!}
     "▶ Start Cell"]]])

(defn ^:private state-tree
  "Recursively renders a Clojure data structure as a collapsible tree view."
  [data path]
  (cond
    (map? data)
    [:div.tree-map
     (doall
      (map (fn [[k v]]
             ^{:key (str path "-" k)}
             [:div.tree-entry
              [:span.tree-key (pr-str k)]
              [state-tree v (conj path k)]])
           data))]

    (sequential? data)
    [:div.tree-seq
     (if (empty? data)
       [:span.tree-empty "[]"]
       (doall
        (map-indexed
         (fn [i v]
           ^{:key (str path "-" i)}
           [:div.tree-entry
            [:span.tree-idx (str "[" i "]")]
            [state-tree v (conj path i)]])
         data)))]

    :else
    [:span.tree-val (pr-str data)]))

(defn ^:private dashboard-view
  "Renders the live dashboard with cell state tree, event log, and event injection."
  []
  (let [{:keys [cell-state events event-input]} @app-state]
    [:div.dashboard-container
     [:div.dash-header
      [:h1 "🧠 Mealy Cell Dashboard"]
      [:div.status-row
       [:span.status-dot] [:span "Cell Active"]]]

     [:div.dashboard-grid
      ;; State panel
      [:div.panel.state-panel
       [:div.panel-header
        [:h2 "Cell State"]
        [:span.badge-live "● LIVE"]]
       [:div.panel-body
        (if cell-state
          [state-tree cell-state []]
          [:div.placeholder "Waiting for state..."])]]

      ;; Events panel
      [:div.panel.events-panel
       [:div.panel-header
        [:h2 "Event Log"]
        [:span.event-count (str (count events) " events")]]
       [:div.panel-body.event-list
        (if (seq events)
          (doall
           (map-indexed
            (fn [i evt]
              ^{:key i}
              [:div.event-row
               [:span.event-idx (str "#" (inc i))]
               [:code.event-data (pr-str evt)]])
            (reverse events)))
          [:div.placeholder "No events yet..."])]]]

     ;; Event injection
     [:div.inject-section
      [:h3 "Inject Event"]
      [:p.inject-hint "Enter an EDN event vector, e.g. [:observation {:type :test}]"]
      [:div.inject-row
       [:input.inject-input
        {:type "text"
         :value event-input
         :placeholder "[:observation {:type :test :data \"hello\"}]"
         :on-change #(swap! app-state assoc :event-input (.. % -target -value))
         :on-key-down #(when (= (.-key %) "Enter") (send-event!))}]
       [:button.btn-send
        {:on-click send-event!}
        "Send →"]]]]))

;; ---------------------------------------------------------------------------
;; Root
;; ---------------------------------------------------------------------------

(defn ^:private root
  "Top-level Reagent component routing between config and dashboard views."
  []
  [:div#app-root
   (case (:view @app-state)
     :config [config-view]
     :dashboard [dashboard-view])])

(defonce ^{:doc "React 18 root instance." :private true}
  react-root (rdc/create-root (.getElementById js/document "app")))

(defn ^:export init!
  "Entry point to mount the Reagent app using React 18 createRoot."
  []
  (rdc/render react-root [root]))

(defn -main
  "Main entry point — delegates to init!."
  [& _args]
  (init!))

;; Auto-mount on namespace load
(init!)
