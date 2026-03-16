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
- [ ] **Dependency Update:** Add `hato/hato {:mvn/version "1.0.0"}` to `deps.edn` to provide a lean, asynchronous Java 11+ HTTP client.
- [ ] **Generalize the Actuator:** Refactor `mealy.ooda.actuator` into a generic `mealy.intelligence.gateway`. It must blindly forward `{:type :llm-request, :prompt "...", :callback-event :my-event}` commands to the Router, and wrap the raw string response in `[:observation {:type :my-event, :response "..."}]`. Move `parse-consent` logic completely out of the IO boundary and into the pure Cell reducer.
- [ ] **Gemini Adapter:** Implement `mealy.intelligence.adapters.gemini`. Use `hato/request-async` to build a non-blocking Provider Actor that interfaces securely with the Gemini REST API.
- [ ] **Llama Adapter:** Implement `mealy.intelligence.adapters.llama`. Use `hato/request-async` to build a non-blocking Provider Actor that interfaces with a local Llama instance (e.g., via the local Ollama REST API).
