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
