(ns dev.core
  "Dev dashboard server for Mealy.
   Starts an http-kit server serving a ClojureScript SPA
   and exposes EDN API endpoints to configure, start, and observe a Mealy cell."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [mealy.cell.core :as cell]
            [mealy.cell.reducer :as reducer]
            [mealy.runtime.jvm.core :as rcore]
            [mealy.runtime.protocols :as p]
            [mealy.runtime.protocols-test :refer [->MemoryEventStore ->MemoryEventBus]]
            [org.httpkit.server :as http-kit]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :as resp])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Global state
;; ---------------------------------------------------------------------------

(defonce ^{:doc "The running http-kit server instance." :private true}
  server (atom nil))

(defonce ^{:doc "Active cell runtime state: store, bus, channels, node." :private true}
  cell-state (atom nil))

(defn- serialize-state
  "Converts cell state to a serializable map, stripping function values."
  [state]
  (when state
    (-> state
        (update :handlers (fn [h] (into {} (map (fn [[k _]] [k "<handler>"]) h))))
        (update :actions (fn [a] (into {} (map (fn [[k _]] [k "<action>"]) a))))
        (update :children #(vec %)))))

(defn- edn-response
  "Builds a ring response with EDN content type."
  [data]
  (-> (resp/response (pr-str data))
      (resp/content-type "application/edn")))

;; ---------------------------------------------------------------------------
;; API handlers
;; ---------------------------------------------------------------------------

(defn- handle-get-state
  "Returns the current cell state as EDN by replaying events from the MemoryEventStore."
  [_req]
  (if-let [cs @cell-state]
    (let [store (:store cs)
          id (:id cs)
          initial (:initial-state cs)
          all-events (p/get store id)
          reconstructed (reduce (fn [s e]
                                  (:state (reducer/handle-event s e)))
                                initial
                                all-events)]
      (edn-response (serialize-state reconstructed)))
    (edn-response nil)))

(defn- handle-get-events
  "Returns all persisted events for the active cell as EDN."
  [_req]
  (if-let [cs @cell-state]
    (let [store (:store cs)
          id (:id cs)
          events (p/get store id)]
      (edn-response (vec events)))
    (edn-response [])))

(defn- handle-start-cell
  "Starts a new Mealy cell from the EDN config in the request body."
  [req]
  (if @cell-state
    (-> (edn-response {:error "Cell already running. Restart the server to reset."})
        (resp/status 409))
    (let [body (edn/read-string (slurp (:body req)))
          aim (:aim body)
          adapters (:adapters body)
          providers (into {}
                          (map-indexed
                           (fn [i adapter]
                             [(keyword (str "p" i)) adapter]))
                          adapters)
          initial-state (cell/make-cell aim {:providers providers})
          store (->MemoryEventStore (atom {}))
          bus (->MemoryEventBus (atom {}))
          cell-id :dev-cell
          in-chan (async/chan 100)
          out-chan (async/chan 100)
          node (rcore/start-node store bus cell-id initial-state in-chan out-chan
                                 {:workers 1
                                  :cell-in-chan in-chan})]
      (reset! cell-state {:store store
                          :bus bus
                          :id cell-id
                          :initial-state initial-state
                          :in-chan in-chan
                          :out-chan out-chan
                          :node node})
      (edn-response {:status :started
                     :aim aim
                     :providers (count providers)}))))

(defn- handle-send-event
  "Injects an event into the active cell's input channel.
   Expects an EDN vector like [:observation {:type :foo}]."
  [req]
  (if-let [cs @cell-state]
    (let [event (edn/read-string (slurp (:body req)))
          in-chan (:in-chan cs)]
      (async/put! in-chan event)
      (edn-response {:status :sent :event event}))
    (-> (edn-response {:error "No cell running."})
        (resp/status 400))))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(defroutes ^{:doc "Compojure route table for the dev dashboard EDN API."} app-routes
  (GET "/" [] (-> (resp/resource-response "public/index.html")
                  (resp/content-type "text/html")))
  (GET "/api/state" req (handle-get-state req))
  (GET "/api/events" req (handle-get-events req))
  (POST "/api/cell/start" req (handle-start-cell req))
  (POST "/api/cell/event" req (handle-send-event req))
  (route/not-found "Not Found"))

(def ^{:doc "Ring handler stack: routes + static resource serving + API defaults."} app
  (-> app-routes
      (wrap-resource "public")
      (wrap-defaults api-defaults)))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn start-server!
  "Starts the http-kit server on the given port."
  [port]
  (println (str "🧠 Mealy Dev Dashboard starting on http://localhost:" port))
  (reset! server (http-kit/run-server app {:port port})))

(defn stop-server!
  "Gracefully stops the server and cleans up cell state."
  []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)
    (when-let [cs @cell-state]
      (async/close! (:in-chan cs)))
    (reset! cell-state nil)
    (println "Server stopped.")))

(defn -main
  "Entry point. Reads PORT from env or defaults to 8000."
  [& _args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8000"))]
    (start-server! port)))
