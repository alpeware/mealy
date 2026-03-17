# Mealy Implementation Roadmap

## Phase 1: The Pure Cell (Atomic State Machine)
- [x] Implement `mealy.cell.core`: define the core `Cell` data structure (Aim, Memory, Observations).
- [x] Implement `mealy.cell.reducer`: the pure Sans-IO `(handle-event state event)` Mealy machine.
- [x] Implement `mealy.cell.mitosis`: pure functions to split a parent state map into a child Genesis State based on threshold parameters.

## Phase 2: The Execution Shell (Autopoiesis)
- [x] Implement `mealy.shell.core`: the `go-loop` adapter that multiplexes `:in-chan` and routes `:out-chan` commands.
- [x] Implement `mealy.shell.topology`: the dynamic channel-wiring logic that executes `{:type :replicate}` commands, binding new children to parents.

## Phase 3: The Sociocratic OODA Loop
- [x] Implement `mealy.ooda.prompt`: pure functions to compile Aim, Memory, and Observations into a Consent-based LLM evaluation prompt.
- [x] Implement `mealy.ooda.actuator`: the IO boundary for submitting prompts to the LLM and parsing the Consent result back into the Cell.

## Phase 4: Intelligence Routing & Token Economics
- [x] **Provider Actors:** Implement `mealy.intelligence.provider`. Create a stateful `go-loop` component that wraps a specific LLM API/Local Model. It must maintain an internal state of its token budget, rate limits (RPM/TPM), and backoff status. It must use non-blocking async I/O to fetch responses.
- [x] **Intelligence Router:** Implement `mealy.intelligence.router`. Create a multiplexing `go-loop` that sits between the Cell Actuators and the Providers. It receives `evaluate-prompt` commands, inspects the current health and token budgets of all registered Provider Actors, and routes the prompt to the most economically optimal model based on the Cell's required complexity.
- [x] **Actuator Refactor:** Refactor `mealy.ooda.actuator` to send prompts to the Intelligence Router's channel instead of calling a blocking `llm-fn`.

## Phase 5: Generic Intelligence Gateway & Provider Adapters
- [x] **Dependency Update:** Add `hato/hato {:mvn/version "1.0.0"}` to `deps.edn` to provide a lean, asynchronous Java 11+ HTTP client.
- [x] **Generalize the Actuator:** Refactor `mealy.ooda.actuator` into a generic `mealy.intelligence.gateway`. It must blindly forward `{:type :llm-request, :prompt "...", :callback-event :my-event}` commands to the Router, and wrap the raw string response in `[:observation {:type :my-event, :response "..."}]`. Move `parse-consent` logic completely out of the IO boundary and into the pure Cell reducer.
- [x] **Gemini Adapter:** Implement `mealy.intelligence.adapters.gemini`. Use `hato/request-async` to build a non-blocking Provider Actor that interfaces securely with the Gemini REST API.
- [x] **Llama Adapter:** Implement `mealy.intelligence.adapters.llama`. Use `hato/request-async` to build a non-blocking Provider Actor that interfaces with a local Llama instance (e.g., via the local Ollama REST API).

## Phase 6: Von Neumann Action Registry & Extensibility
- [x] **Action Registry Foundation:** Implement `mealy.action.core`. Define a multimethod `(defmulti execute (fn [action env] (:type action)))` to act as the extensible execution router. The `env` map will carry system contexts (e.g., `:gateway-chan`, `:cell-in-chan`).
- [x] **The `:think` Action:** Implement `(defmethod execute :think ...)`. This action must extract a `:prompt` from the action map, construct a standard `{:type :llm-request, :prompt ..., :callback-event :thought-result}` command, and push it to the `gateway-chan` so the agent can delegate cognitive tasks.
- [x] **The `:eval` Action (SCI Sandbox):** Implement `(defmethod execute :eval ...)`. This action extracts a `:code` string and evaluates it using `sci/eval-string`. Crucially, configure the `sci` context to expose the `mealy.action.core` namespace and the `execute` multimethod. This allows evaluated code to dynamically define new `defmethod`s. Catch exceptions and route results back to the Cell via `[:observation {:type :eval-success/:eval-error, ...}]`.
- [x] **Von Neumann Integration Tests:** Implement `test/mealy/action/core_test.clj`. Write tests to verify `:think` forwards to the gateway. Write a definitive integration test proving self-modification: send an `:eval` action containing code that defines a `(defmethod execute :new-skill ...)`, then send a `:new-skill` action and assert it executes successfully.

## Phase 7: Event Sourcing, Persistence & Cognitive Reflexes
- [x] **The Append-Only Event Log:** Update the IO Shell (`mealy.shell.core`). Before passing an incoming event from the `:in-chan` to the pure `reducer`, the shell must append the raw event to a persistent, append-only log file on disk (e.g., `events.log`).
- [x] **Nippy State Snapshots:** Implement a snapshot mechanism in the IO Shell. Periodically (or upon major phase transitions/mitosis), the shell must serialize the entire pure `state` map to disk using `taoensso.nippy/freeze-to-file`. This prevents the bootloader from having to replay millions of historical events.
- [x] **The Bootloader (Crash Recovery):** Implement a `restore-cell` startup routine in the shell. Upon booting, it must locate the latest Nippy snapshot, deserialize it (`nippy/thaw-from-file`), and then read the `events.log`. It must replay any events appended *after* the snapshot timestamp through the pure `reducer/handle-event` to perfectly reconstruct the Cell's exact state before spinning up the `go-loop`.
- [x] **Dynamic Tool Awareness:** Update `mealy.ooda.prompt/compile-prompt`. It must dynamically inspect the currently registered methods in the `mealy.action.core/execute` multimethod (e.g., using `methods`) and inject that list of available tools directly into the LLM prompt. This gives the agent physical self-awareness of its own capabilities. Leverage metadata like doc strings to provide context.
- [x] **Autonomic Reflexes (The Fast Path):** Update `mealy.cell.reducer`. The Cell's `:memory` can now contain a `:reflexes` map (e.g., `{:cpu-temp-high {:type :throttle-cpu}}`). When an `:observation` arrives, the pure reducer must check this map BEFORE transitioning to `:evaluating`. If a reflex matches, it must instantly yield the mapped command and remain `:idle`, bypassing the LLM completely.
- [ ] **The Propose Policy Action:** Add a new pure state transition for `[:propose-policy {:code "..."}]`. This buffers proposed `defmethod` Clojure code in the state, awaiting a future committee consent evaluation before it can be routed to the `:eval` action sink.
