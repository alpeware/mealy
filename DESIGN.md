# Mealy Architecture Overview

## 1. System Overview

`mealy` is a Clojure-based, pure-functional orchestration framework for building autopoietic (self-regulating), autonomous agent topologies. It is designed to allow a single human architect to operate a massively scalable, self-organizing digital enterprise.

The system abandons flat mesh network topologies and monolithic optimization loops. Instead, it models the enterprise as a fractal holarchy of nested deterministic state machines (Mealy machines) called **Cells**. Cells self-regulate, evaluate decisions via qualitative LLM-based consent, and divide (mitosis) when complexity thresholds are breached.

## 2. Core Technologies & Constraints

* **Language:** Clojure.
* **Concurrency:** `clojure.core.async`. **Strict Constraint:** We explicitly *do not* use `clojure.core.async.flow`. The routing and process topology must be built directly using `core.async` channels and `go-loops`.
* **Architecture:** Strict Sans-IO. The business logic must exist entirely as pure data-in, data-out reducers. Side-effects are strictly isolated to a thin execution shell.

## 3. The Cellular Holarchy (Topology)

The system is structured as a fractal tree of Mealy machines.

* **The Cell (Mealy Machine):** The atomic unit of the system. A Cell is a pure function: `(handle-event state event) -> {:state new-state :commands [...]}`.
* The state map contains the Cell's **Aim** (its objective), **Memory** (policies, budget, context), and **Observations** (recent events).


* **The Control Plane (Vertical Double-Linking):** The system scales via a strict parent-child tree topology. When a Cell spawns a sub-Cell, the execution shell wires a bidirectional `core.async` channel bridge: `[parent-tx -> child-rx]` and `[child-tx -> parent-rx]`.
* Parents route resources (budgets) and policies *down*.
* Children route anomalies, consolidated metrics, and requests for peer-to-peer introductions *up*.


* **The Data Plane (Horizontal Connectivity):** To avoid latency bottlenecks in high-throughput domains (e.g., streaming live market data for algorithmic options trading), Cells can request horizontal connections to peers. The Control Plane authorizes the connection, but the heavy data flows horizontally (e.g., via Bitecho WebRTC data channels) bypassing the parent bottleneck.

## 4. The Sociocratic OODA Loop (Decision Engine)

Cells do not rely on hardcoded mathematical loss functions for complex decisions. They utilize an LLM-as-a-Judge pattern, executing a qualitative OODA loop on every System Tick.

1. **Observe:** Oracles (pure I/O boundaries) push objective events `[:observation ...]` into the Cell's input channel.
2. **Orient:** The Cell updates its state map, contextualizing the observation against its Aim and Memory (policies).
3. **Decide (Consent, not Consensus):** If an action is required, an LLM prompt is constructed from the Aim, Memory, and Observations. The LLM evaluates proposals using the sociocratic principle of **Consent**: *"Is there a paramount, reasoned policy objection to this proposal?"* If no paramount objection exists, the decision is approved.
4. **Act:** The pure reducer yields a `[:command ...]` map to the shell.

## 5. Autopoiesis & Cellular Mitosis

Cells monitor their own cognitive load, state bloat, and resource consumption. The system scales dynamically without central orchestration.

* **The Mitosis Trigger:** If a Cell evaluates that its complexity has breached a defined threshold (e.g., managing too many data streams, exhausting its compute window), it yields a `{:type :replicate}` command to the shell.
* **Bifurcation:** The Cell provides the shell with a carved-out Genesis State for the new child. This state contains a subset of the parent's Memory and a strictly partitioned slice of its token/compute budget.
* **Birth:** The Sans-IO shell dynamically spins up a new `core.async` go-loop for the child, wires the double-linked channels to the parent, and initializes the new Mealy machine.

## 6. The Execution Shell (Sans-IO Boundary)

Because we are dropping `core.async.flow`, the library must provide a standard execution shell to bind the pure Mealy machine to the dirty JVM runtime.

* **The Process Loop:** Each Cell is wrapped in a dedicated `core.async` `go-loop`.
* **Ingress (`:in-chan`):** Multiplexes clock ticks, parent control messages, and external Oracle data.
* **Egress (`:out-chan`):** The loop takes the `[:commands ...]` returned by the pure reducer and routes them.
* Commands targeting other Cells are placed on the respective double-linked channels.
* Commands targeting the real world (e.g., executing a trade, calling an LLM API) are routed to specialized, dumb **IO Actuator** queues.

## 7. Intelligence Routing & Token Economics

To decouple the pure Mealy machine from specific LLM vendors, the system implements an Intelligence Routing layer:

* **Provider Actors:** Stateful `go-loop` components (`mealy.intelligence.provider`) wrap specific LLM APIs (e.g., Gemini, Llama) or local models using non-blocking async HTTP (`hato`). They maintain internal state for token budgets, rate limits, and backoff statuses.
* **Intelligence Router:** A multiplexing `go-loop` (`mealy.intelligence.router`) that sits between the Cells and the Providers. It receives `evaluate-prompt` commands, inspects the current health, token budgets, and capabilities of all registered Provider Actors, and routes the prompt to the most economically optimal model based on the Cell's required complexity.
* **Generic Intelligence Gateway:** A pure IO boundary (`mealy.intelligence.gateway`) that blindly forwards `{:type :llm-request}` commands to the Router, and wraps the raw string response back into the Cell as an `[:observation ...]` event.

## 8. Von Neumann Action Registry & Extensibility

The agent's execution layer implements a Von Neumann architecture for runtime self-modification.

* **The Execution Router:** A central, extensible shell-side action registry built on a Clojure `defmulti` (`mealy.action.core/execute`). It dispatches based on the `:type` of the action map.
* **Cognitive Delegation:** The baseline `:think` action pushes standard LLM requests back to the Intelligence Gateway, allowing the agent to delegate arbitrary cognitive subtasks.
* **Dynamic Skill Acquisition:** The `:eval` action extracts a `:code` string and evaluates it using a sandboxed `sci` (Small Clojure Interpreter) environment. The sandbox exposes the `execute` multimethod, allowing the agent to dynamically write, evaluate, and inject new `defmethod` execution paths (with side effects) into its own runtime registry.

## 9. Event Sourcing, Persistence & Cognitive Reflexes

To ensure crash-fault tolerance and predictable high-speed operation, the pure Cell utilizes Event Sourcing and Reflexes.

* **Append-Only Event Log:** Before passing an incoming event to the pure reducer, the IO shell appends the raw event to a persistent `events.log` on disk.
* **Nippy Snapshots:** Periodically, the IO shell serializes the entire pure `state` map to disk using `taoensso.nippy`.
* **The Bootloader:** Upon booting, the system locates the latest Nippy snapshot, deserializes it, and replays only the events appended *after* the snapshot timestamp to perfectly reconstruct the Cell's exact state.
* **Dynamic Tool Awareness:** The `mealy.ooda.prompt` compiler dynamically inspects the `mealy.action.core/execute` registry to inject the current list of available tools (and their metadata/docstrings) directly into the LLM prompt, giving the agent physical self-awareness of its evolving capabilities.
* **Autonomic Reflexes:** The Cell's Memory can contain a `:reflexes` map. When a known observation arrives, the pure reducer checks this map before transitioning to the LLM. If matched, it instantly yields the mapped command (e.g., `{:throttle-cpu true}`) bypassing the LLM completely for deterministic, zero-latency reactions.
* **Policy Proposals:** The `[:propose-policy]` action buffers proposed Clojure code in the state. This requires a committee consent evaluation via the LLM before the code is routed to the `:eval` action sink.
