# Mealy Implementation Roadmap

## Phase 1: The Pure Cell (Atomic State Machine)
- [x] Implement `mealy.cell.core`: define the core `Cell` data structure (Aim, Memory, Observations).
- [x] Implement `mealy.cell.reducer`: the pure Sans-IO `(handle-event state event)` Mealy machine.
- [x] Implement `mealy.cell.mitosis`: pure functions to split a parent state map into a child Genesis State based on threshold parameters.

## Phase 2: The Execution Shell (Autopoiesis)
- [ ] Implement `mealy.shell.core`: the `go-loop` adapter that multiplexes `:in-chan` and routes `:out-chan` commands.
- [ ] Implement `mealy.shell.topology`: the dynamic channel-wiring logic that executes `{:type :replicate}` commands, binding new children to parents.

## Phase 3: The Sociocratic OODA Loop
- [ ] Implement `mealy.ooda.prompt`: pure functions to compile Aim, Memory, and Observations into a Consent-based LLM evaluation prompt.
- [ ] Implement `mealy.ooda.actuator`: the IO boundary for submitting prompts to the LLM and parsing the Consent result back into the Cell.
