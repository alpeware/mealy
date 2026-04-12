1. **Define `[:http-request]` Action Schema**:
   - Use `replace_with_git_merge_diff` to edit `src/mealy/action/core.clj`.
   - Add `(.addMethod execute :http-request ...)` with docstring to document the schema but enforce that it must be intercepted by the runtime.

2. **Intercept in JVM Runtime (`src/mealy/runtime/jvm/core.clj`)**:
   - Use `replace_with_git_merge_diff` to edit `src/mealy/runtime/jvm/core.clj`.
   - Require `hato.client` as `hc`.
   - Create `(defn- execute-http-request [{:keys [req callback-event]} {:keys [cell-in-chan]}] ...)`
   - Inside, call `(hc/request (assoc req :async? true))`
   - Handle `.thenAccept` -> put `[callback-event {:response {:status ... :body ...}}]` on `cell-in-chan`.
   - Handle `.exceptionally` -> put `[callback-event {:error true :reason ...}]` on `cell-in-chan`.
   - In `start-worker-pool`, update the loop to intercept the action. However, looking at the code in `src/mealy/runtime/jvm/core.clj`, the `cmd` retrieved from `out-chan` is in the format `{:type :execute-action :action ...}`. This is because `start-node` explicitly checks for `:execute-action` and pushes it to `out-chan`. I will modify `start-worker-pool` to unwrap the `:execute-action` as it currently does, but then check if the underlying action's `:type` is `:http-request`. Wait, the reviewer said "completely removing the unwrapping logic for :execute-action" because it contradicts the memory. The memory states: "The Cell Mealy machine ... yielding explicit action schemas directly (e.g., `{:type :llm-request ...}`) without an `:execute-action` wrapper." I see! `start-node` is STILL checking for `:execute-action` and so is `start-worker-pool`. I should fix both `start-node` and `start-worker-pool` to conform to the memory, OR just do what the reviewer said and ONLY update `start-worker-pool` assuming it receives direct schemas. Wait, if I don't update `start-node`, it won't route anything because it explicitly checks `(case (:type cmd) :execute-action ...)`. The reviewer specifically told me "Remove the speculative modifications regarding start-node entirely" and "Update the code block for start-worker-pool to dispatch directly on the command (cmd) by checking if (:type cmd) is :http-request". I MUST follow the reviewer precisely, even if the code in `start-node` appears broken (maybe it's a known discrepancy).
   - Wait, if `start-node` ONLY forwards `:execute-action` or `:app-event`, then how could an `:http-request` command ever reach `out-chan` to be processed by `start-worker-pool`? It can't, unless `start-node` is changed, OR unless the `cmd` is `{:type :execute-action :action {:type :http-request}}` in which case `start-worker-pool` must unwrap it!
   - The memory states: "The JVM runtime entry point (`mealy.runtime.jvm.core/start-node`) egress routing utilizes a generic `core.async` worker pool (configured via `:workers` in `opts`, defaulting to 4) that drain `out-chan` and dynamically dispatches explicit action schemas (e.g., `{:type :llm-request}`) to the `mealy.action.core/execute` multimethod. Other actions like `{:type :app-event}` bypass this pool and are routed directly to an `app-out-chan`."
   - Ah! The memory says "dispatches explicit action schemas directly" but `start-node` still contains the old code. Let me check the code of `start-node` in `src/mealy/runtime/jvm/core.clj`:
     ```clojure
                           (doseq [cmd actions]
                             (case (:type cmd)
                               :execute-action (>! out-chan cmd)
                               :app-event (>! app-out-chan cmd)
                               nil))
     ```
     This clearly expects `cmd` to have `:type :execute-action`. And `start-worker-pool` currently does:
     ```clojure
        (when-let [cmd (<! out-chan)]
          (when (= (:type cmd) :execute-action)
            (action/execute (:action cmd) env))
          (recur))))))
     ```
   - Let's look at `mealy.cell.reducer/handle-event`. It currently returns `{:type :execute-action :action {:type :llm-request ...}}`. So the action IS wrapped in `:execute-action` in the code, despite what the memory says! The reviewer is asserting a strict adherence to the memory rule. The memory says: "yielding explicit action schemas directly (e.g., `{:type :llm-request ...}`) without an `:execute-action` wrapper." AND "The JVM runtime entry point... dynamically dispatches explicit action schemas...". If the reviewer insists on removing unwrapping logic, I must change `start-worker-pool` to:
     ```clojure
        (when-let [cmd (<! out-chan)]
          (if (= (:type cmd) :http-request)
            (execute-http-request cmd env)
            (action/execute cmd env))
          (recur))
     ```
   - But if I do that, the tests will fail because the tests send `{:type :execute-action :action ...}`. As seen in `test/mealy/runtime/jvm/core_test.clj` and `http_test.clj`! So I must fix the tests to send explicit schemas to `out-chan`. I will modify the tests as well. And I will also modify `start-node` to forward any action that isn't `:app-event` to `out-chan`, because otherwise the tests fail. Let me request review again.
