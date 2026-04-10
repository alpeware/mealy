# Mealy Architecture Overview

## 1. System Overview

`mealy` is a Clojure/ClojureScript pure-functional orchestration framework for building autopoietic (self-regulating), autonomous agent topologies. It models an enterprise as a fractal holarchy of nested deterministic state machines called **Cells**. 

To allow a single human architect to operate a massively scalable digital entity, the core library is strictly Sans-IO. The pure cellular logic is entirely decoupled from its execution environment, allowing the exact same agent logic to run seamlessly across App Engine, browsers, headless JVM/Node backends, and mobile devices via injected **Runtimes**.

## 2. Core Architecture: Handlers vs. Actions

The system abandons merged state-and-effect reducers in favor of a strict dichotomy between evaluating the past (Handlers) and affecting the future (Actions).

* **Handlers (Pure Events):** Handlers are pure, deterministic functions (`data -> data`) that react to incoming events. They update the Cell's internal state and yield a queue of intents (Commands/Actions).
* **Actions (Impure Side Effects):** Actions represent the execution of intent. They are strictly side-effecting sinks managed by the Runtime, reacting to the commands yielded by Handlers.

## 3. The Expanded Cell State

To support true dynamic self-modification and dynamic routing without hardcoded shell configurations, the Cell state treats functions and subscriptions as data, evaluated safely within forked `sci` (Small Clojure Interpreter) contexts.

A standard Cell State includes:
* `:aim` - The qualitative objective of the agent.
* `:memory` - The working memory, budget, policies, and context log.
* `:subscriptions` - Active event sources the Cell is listening to.
* `:handlers` - The dynamic registry of pure event reducers (stored as data/code).
* `:actions` - The dynamic registry of available side-effect capabilities.
* `:parent` - Reference to the upstream control plane.
* `:children` - References to spawned sub-cells.

## 4. The Sociocratic OODA Loop (Decision Engine)

Cells do not rely on hardcoded mathematical loss functions for complex decisions. Event processing follows a continuous, recursive OODA cycle utilizing an LLM-as-a-Judge pattern:

1.  **Observe (Event):** The Runtime pushes an event from a subscribed source (e.g., `[:observation {:type :market-data ...}]`) into the Cell.
2.  **Orient (State):** Handlers process the event. The Cell contextualizes the observation against its `:aim` and `:memory` (policies).
3.  **Decide (LLM Consent vs. Reflex):** * *Autonomic Reflexes:* If a known pattern is matched in memory, the Cell triggers a mapped reflex (e.g., `[:throttle-cpu]`), bypassing the LLM completely for zero-latency reactions.
    * *Sociocratic Consent:* For novel decisions, the Cell evaluates the state against its Goal. It compiles an LLM prompt and requests a qualitative evaluation based on the sociocratic principle of **Consent**: *"Is there a paramount, reasoned policy objection to this proposal?"*
4.  **Act (Function):** If no paramount objection exists, the Cell yields explicit Actions to the Runtime executor, resulting in Effects and Feedback loops back into the Memory Log.

## 5. Dynamic Subscriptions & The Event Bus

Cells dynamically control their data intake. Instead of the Runtime multiplexing everything blindly, Cells emit Actions to turn subscriptions on or off. 

For high-throughput domains (like ingesting 0 DTE options tick data), a Cell can dynamically request a new subscription (`[:subscribe {:source :market-stream}]`). The Runtime authorizes this via the Control Plane but routes the heavy data horizontally (e.g., via WebRTC data channels) directly into the Cell's event queue, bypassing parent bottlenecks.

## 6. Autopoiesis & Cellular Mitosis

Cells continuously monitor their own cognitive load, state bloat, and resource consumption. The system scales dynamically without central orchestration.

* **The Mitosis Trigger:** If a Cell evaluates that its complexity has breached a defined threshold (e.g., managing too many data streams), it yields a `[:spawn {:memory-slice ... :aim ...}]` Action.
* **Bifurcation:** The Cell provides the Runtime with a carved-out Genesis State for the new child, containing a subset of the parent's `:memory` and a strictly partitioned token/compute budget.
* **Birth:** The Runtime dynamically registers the new Cell on the Event Bus, wires the parent-child event subscriptions, and initializes the new autonomous loop.

## 7. Intelligence Routing & Token Economics

To decouple the pure Mealy machine from specific LLM vendors and manage financial constraints, the Runtime implements an Intelligence Routing layer for all `[:llm-request]` Actions.

* **Provider Actors:** Specific external LLM APIs (e.g., Gemini, local Llama instances) are represented as managed Runtime executors with internal state for token budgets, rate limits, and backoff statuses.
* **Intelligence Router:** When a Cell yields an `[:llm-request {:complexity :high}]` Action, the Runtime's Router intercepts it. It inspects the current health, token budgets, and capabilities of all registered Providers, and routes the prompt to the most economically optimal model based on the Cell's requested complexity.

## 8. Von Neumann Action Registry & Extensibility

The agent implements a Von Neumann architecture for runtime self-modification (neuroplasticity), allowing it to write and adopt new skills on the fly.

* **Dynamic Skill Acquisition:** A Cell can yield an `[:eval {:form "(defmethod ...)"}]` Action containing newly proposed Clojure code.
* **The SCI Sandbox:** The Runtime evaluates this code string using a sandboxed `sci` environment. Because the Cell's `:handlers` and `:actions` registries are part of its expanded state, the evaluated code can dynamically inject new execution paths into the agent's own runtime.
* **Dynamic Tool Awareness:** During the "Decide" phase, the Cell dynamically inspects its current `:actions` registry and injects the list of available tools (and their docstrings) into the LLM prompt, giving the agent physical self-awareness of its evolving capabilities.

## 9. Abstract Runtimes (The IO Boundary)

The core `mealy` library contains zero `core.async` or JVM-specific file IO. To host a Cell, an environment must provide a Runtime implementation that fulfills two distinct interface contracts:

### A. The Event Bus Interface
Handles all ephemeral routing and pub/sub.
* `register` - Bind a new Cell to the bus.
* `subscribe` - Connect a Cell to a specific event topic or peer.
* `publish` - Broadcast an event to subscribers.
* `query` - Fetch current ephemeral state from the bus.

### B. The Event Store Interface
Handles all persistence, Event Sourcing, and crash-fault recovery.
* `snapshot` - Serialize the current expanded State map (including `:handlers` and `:actions` data) using tools like Nippy.
* `put` - Append an incoming event to the append-only Event Log.
* `load` - Rehydrate the State by locating the latest snapshot, deserializing it, and replaying any subsequent events from the log.
* `get` - Retrieve historical events for auditing or RAG (Retrieval-Augmented Generation).
