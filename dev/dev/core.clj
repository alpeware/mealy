(ns dev.core
  "Dev dashboard server for Mealy.
   Starts an http-kit server serving a ClojureScript SPA
   and exposes EDN API endpoints to configure, start, and observe a Mealy cell."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [mealy.cell.core :as cell]
            [mealy.runtime.jvm.core :as rcore]
            [mealy.runtime.protocols :as p]
            [mealy.runtime.protocols-test :refer [->MemoryEventStore ->MemoryEventBus]]
            [mealy.subscription.core :as sub]
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

(defonce ^{:doc "Active cells runtime state map: {cell-id {...}}." :private true}
  cell-state (atom {}))

(defonce ^{:doc "App events received from the cell's app-out-chan." :private true}
  app-events (atom []))

(defn- serialize-state
  "Converts cell state to a serializable map, stripping function values."
  [state]
  (when state
    (-> state
        (dissoc :sci-ctx))))

(defn- edn-response
  "Builds a ring response with EDN content type."
  [data]
  (-> (resp/response (pr-str data))
      (resp/content-type "application/edn")))

;; ---------------------------------------------------------------------------
;; API handlers
;; ---------------------------------------------------------------------------

(defn- handle-get-state
  "Returns the live cell states as an aggregated EDN map."
  [_req]
  (let [cs-map @cell-state]
    (if (seq cs-map)
      (edn-response (into {} (map (fn [[cid ctx]] [cid (serialize-state @(:live-state ctx))]) cs-map)))
      (edn-response nil))))

(defn- handle-get-events
  "Returns all persisted events for all cells as an aggregated EDN map."
  [_req]
  (let [cs-map @cell-state]
    (if (seq cs-map)
      (let [store (:store (val (first cs-map)))
            events-map (into {} (map (fn [[cid _]] [cid (p/get store cid)]) cs-map))]
        (edn-response events-map))
      (edn-response {}))))

(defn- handle-get-app-events
  "Returns all app events received from the cell as EDN."
  [_req]
  (edn-response @app-events))

(declare spawn-child-node!)

(defn- start-app-event-consumer!
  "Starts a go-loop that drains the app-out-chan and accumulates app events."
  [app-out-chan]
  (async/go-loop []
    (when-let [evt (async/<! app-out-chan)]
      (if (= (:event-type evt) :spawn-child)
        (let [child-state (:child-state evt)
              child-id (keyword (gensym "child-"))]
          (spawn-child-node! (assoc child-state :id child-id) child-id))
        (swap! app-events conj evt))
      (recur))))

(defn- spawn-child-node!
  "Dynamically provisions a new runtime node for a child cell on the same bus."
  [child-state child-id]
  (if-let [root-cs (get @cell-state :root)]
    (let [in-chan (async/chan 100)
          out-chan (async/chan 100)
          live-state (atom child-state)
          store (:store root-cs)
          bus (:bus root-cs)
          node (rcore/start-node store bus child-id child-state in-chan out-chan
                                 {:workers 1
                                  :cell-in-chan in-chan
                                  :state-atom live-state})
          child-ctx {:store store
                     :bus bus
                     :id child-id
                     :initial-state child-state
                     :live-state live-state
                     :in-chan in-chan
                     :out-chan out-chan
                     :node node
                     :subscriptions []}]
      (start-app-event-consumer! (:app-out-chan node))
      (swap! cell-state assoc child-id child-ctx))
    (println "Cannot spawn child: No root cell running.")))

(defn- handle-start-cell
  "Starts a new Mealy cell from the EDN config in the request body."
  [req]
  (if (seq @cell-state)
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
          initial-state (-> (cell/make-cell aim {:providers providers})
                            (update :bus-topics conj :tick))
          ;; SCI context bootstrap (register-*-ns! + eval bootstrap.clj) is now
          ;; handled automatically by boot/boot-cell! inside start-node.
          store (->MemoryEventStore (atom {}))
          bus (->MemoryEventBus (atom {}))
          cell-id :root
          live-state (atom initial-state)
          in-chan (async/chan 100)
          out-chan (async/chan 100)
          node (rcore/start-node store bus cell-id initial-state in-chan out-chan
                                 {:workers 1
                                  :cell-in-chan in-chan
                                  :state-atom live-state})
          ;; Start the :tick subscription using the pluggable subscription system
          tick-handle (sub/start-subscription {:type :tick :interval-ms 1000}
                                              {:cell-in-chan in-chan})]
      ;; Consume app events from the node
      (start-app-event-consumer! (:app-out-chan node))
      (reset! app-events [])
      (reset! cell-state {cell-id {:store store
                                   :bus bus
                                   :id cell-id
                                   :initial-state initial-state
                                   :live-state live-state
                                   :in-chan in-chan
                                   :out-chan out-chan
                                   :node node
                                   :subscriptions [{:config {:type :tick :interval-ms 1000}
                                                    :handle tick-handle}]}})
      (edn-response {:status :started
                     :aim aim
                     :providers (count providers)}))))

(defn- handle-send-event
  "Injects an event into the root cell's input channel.
   Expects an EDN vector like [:observation {:type :foo}]."
  [req]
  (if-let [cs (get @cell-state :root)]
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
  (GET "/api/app-events" req (handle-get-app-events req))
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
    (doseq [[_ cs] @cell-state]
      ;; Stop all subscriptions
      (doseq [{:keys [config handle]} (:subscriptions cs)]
        (sub/stop-subscription config handle))
      (async/close! (:in-chan cs)))
    (reset! cell-state {})
    (println "Server stopped.")))

(defn -main
  "Entry point. Reads PORT from env or defaults to 8000."
  [& _args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8000"))]
    (start-server! port)))
