# BIT On-Device Agent Harness — Implementation Plan

Derived from `AGENT_HARNESS_SPEC.md` against the current state of `com.bit.agent.harness`.
Status date: 2026-08-23.

---

## 1. Current State (What Already Exists)

| Spec Component | File | Status |
|---|---|---|
| State machine sealed classes | `app/src/main/java/com/bit/agent/harness/state/AgentHarnessState.kt` | ✅ Complete (adds `executionTimeMs`, `partialArtifacts`) |
| Observation contract | `.../harness/model/ToolObservation.kt` | ✅ Complete (3000-char truncation, factory helpers) |
| Gate checker | `.../harness/gate/StepGateChecker.kt` | ✅ Heuristic-only (no LLM verification) |
| Self-correction planner | `.../harness/gate/SelfCorrectionPlanner.kt` | ⚠️ Heuristics done; alternative-tool swap **never applied** by engine |
| Engine | `.../harness/engine/AgentHarnessEngine.kt` | ⚠️ FSM loop runs, but **no LLM decomposition**, no global step cap, no `reset()` |
| Tool bridge | `.../harness/tools/AgentToolBridge.kt` | ⚠️ Bridges `PluginManager.executeToolForMultiTurn` directly |
| DI module | `.../harness/di/HarnessModule.kt` | ✅ Hilt wired |
| Tests | `app/src/test/java/com/bit/agent/harness/AgentHarnessEngineTest.kt` | ⚠️ 5 tests, JVM-only, no Robolectric, no `executeGoal` coverage |

**Not yet built:** `AgentTool` interface + `AgentToolRegistry`, vault/RAG tool suite, LLM-driven
decomposition & semantic gate checks, UI integration (`ChatViewModel`), cancellation/reset lifecycle.

---

## 2. Gap Analysis vs Spec

| # | Spec Requirement | Gap | Severity |
|---|---|---|---|
| G1 | Engine uses `LlmProvider` for goal decomposition (§1, §3.D) | `decomposeGoal()` is keyword heuristics only; engine doesn't inject `LlmProvider` | High |
| G2 | Alternative tool strategy on repeated failure (§4.4) | `planCorrection.alternativeToolName?.let { /* empty */ }` — dead code in `AgentHarnessEngine.kt:121-123` | High |
| G3 | Global step limit enforced across retries (§4.4) | Per-step `while (!stepResolved)` has no bound on total turns vs `maxSteps` | High |
| G4 | `AgentTool` interface + registry across 3 domains (§3.C) | Absent; bridge calls `PluginManager` directly with no schema surface | Medium |
| G5 | Vault/RAG tools: `vault_query`, `vault_remember`, `rag_search_docs` (§3.C.3) | Absent | Medium |
| G6 | Completed → Idle reset transition (§2 state diagram) | No `reset()` on engine | Medium |
| G7 | Compose UI renders step progress from StateFlow (§5) | Nothing consumes `engine.state` (`ChatViewModel` not wired) | Medium |
| G8 | Strict Kotlinx serialization for I/O (§5 checklist) | Engine/planner use `org.json`; only models are kotlinx | Low |
| G9 | Tests pass without device, mocked LLM events (§5 checklist) | `android.util.Log` used in engine/bridge → crashes plain-JVM tests if hit; no `executeGoal` test exists | High |

---

## 3. Phased Plan

### Phase 0 — Baseline Verification (~30 min)
1. Run existing unit tests:
   `./gradlew.bat :app:testDebugUnitTest --tests "com.bit.agent.harness.*"`
2. Compile check:
   `./gradlew.bat :app:compileDebugKotlin`
3. Record results as the regression baseline before touching anything.

### Phase 1 — Engine Correctness Fixes (G2, G3, G6, G9-log)
Files: `AgentHarnessEngine.kt`

1. **Apply alternative tool** (replace empty `let` block):
   ```kotlin
   planCorrection.alternativeToolName?.let { altTool -> step.toolName = altTool }
   ```
   Make `TaskStep.toolName` a `var` in `AgentHarnessState.kt`.
2. **Global turn guard**: inside the retry loop, fail with `Failed("Exceeded maxSteps=$maxSteps")`
   when `turnCount >= maxSteps`. Prevents unbounded retry loops.
3. **Add `reset()`**: sets `_state.value = AgentHarnessState.Idle`; call from UI when a new goal starts.
4. **Log abstraction**: replace `android.util.Log` with an injectable `HarnessLogger` interface
   (default logs to Logcat on Android, no-op/System.err on JVM) so unit tests never hit "not mocked".
5. Update `HarnessModule.kt` provider signature accordingly.

### Phase 2 — LLM-Driven Decomposition & Semantic Gates (G1)
Files: `AgentHarnessEngine.kt`, new `.../gate/LlmGateVerifier.kt`, `HarnessModule.kt`

1. Inject `LlmProvider` into the engine.
2. **LLM decomposition**: prompt the model to emit a JSON array of steps
   (`id, description, toolName, arguments, expectedOutcome`) constrained to registered tools;
   parse with existing `parsePlanJson()`. Fall back to heuristic decomposition when the model
   is unavailable/offline or JSON is malformed (keep current behavior as fallback).
3. **Semantic gate check**: add optional `LlmGateVerifier` used only when heuristic
   `StepGateChecker` is ambiguous (payload present but outcome unclear). Budget: single short
   completion per gate check, capped by `HarnessConfig` (new data class:
   `llmDecomposeEnabled`, `llmGateEnabled`, `maxObservationChars = 3000`, `maxSteps = 10`,
   `maxRetriesPerStep = 3`).
4. Feed `observation.recoveryHint` into the next correction turn per spec §4.3.

### Phase 3 — Formal Tool Layer (G4, G5)
New files under `.../harness/tools/`:

1. `AgentTool.kt` — interface per spec §3.C (`definition: ToolDefinition`, `execute(argumentsJson): ToolObservation`).
2. `AgentToolRegistry.kt` — name→tool map; exposes `definitions(): List<ToolDefinition>` for `ProviderConfig.tools`
   and `get(name)`.
3. `WorkspaceTools.kt` — wrap existing plugin tools (`workspace_read_file`, `workspace_write_file`,
   `workspace_edit_file`, `workspace_shell`) through `PluginManager.executeToolForMultiTurn` so behavior
   matches production chat path.
4. `WebTools.kt` — `web_search`, `web_fetch` wrappers.
5. `VaultTools.kt` — `vault_query`, `vault_remember`, `rag_search_docs` backed by `com.bit.vault.*`
   helpers (verify exact VaultHelper API during this phase).
6. Rewire `AgentToolBridge` to resolve tools through the registry first, falling back to raw
   `PluginManager` dispatch for unknown names (backward compatible).

### Phase 4 — UI Integration (G7)
Files: `app/src/main/java/com/bit/viewmodel/ChatViewModel.kt`, new
`app/src/main/java/com/bit/ui/screen/home/components/HarnessProgressCard.kt`

1. Inject `AgentHarnessEngine` into `ChatViewModel`; expose `val harnessState = engine.state`.
2. Add intent/action in ChatViewModel: `startGoal(goal)` → collect `engine.executeGoal(...)`;
   `cancelGoal()` → cancel the collecting job; `resetGoal()` → `engine.reset()`.
3. Compose card rendering per state: spinner for Decomposing, step x/y progress bar for Executing,
   ✓/✗ chip row for GateChecking, retry badge for SelfCorrecting, summary + artifact list for
   Completed/Failed.
4. Trigger: route goals that match an "agent mode" toggle (or explicit user action) to the harness
   instead of plain chat streaming.

### Phase 5 — Test Hardening (G9)
Files: `app/src/test/java/com/bit/agent/harness/*`, `app/build.gradle.kts`

1. Add fake/mock `LlmProvider` emitting deterministic `StreamEvent.TextChunk` JSON plans — no device needed.
2. New tests:
   - Happy path FSM: Idle→Decomposing→Executing→GateChecking(passed)→Completed (assert emitted sequence).
   - Gate failure → SelfCorrecting → retry passes → Completed.
   - Exhausted retries → Failed with correct reason and `partialArtifacts`.
   - Global `maxSteps` guard trips on endless failing step.
   - Alternative-tool swap actually changes `step.toolName` after 2nd retry.
   - `reset()` returns to Idle.
3. If Robolectric is preferred over logger abstraction, add `testImplementation "org.robolectric:robolectric"`
   and annotate tests — choose ONE strategy (Phase 1 item 4 already removes the need).

---

## 4. Execution Order & Dependencies

```
Phase 0 ──▶ Phase 1 ──▶ Phase 2 ──▶ Phase 5 (run continuously)
                 │
                 └──────▶ Phase 3 ──▶ Phase 4
```
Phase 3 can start in parallel with Phase 2 (independent files). Phase 4 requires Phases 1–3 complete.

## 5. Definition of Done (maps to Spec §5)

- [ ] All tool inputs/outputs flow through kotlinx-serializable models at the harness boundary.
- [ ] Every exception in tool execution becomes `ToolObservation(status=ERROR, recoveryHint=...)`.
- [ ] `engine.state` drives a visible Compose step-progress UI.
- [ ] Full unit suite green on JVM: `./gradlew.bat :app:testDebugUnitTest --tests "com.bit.agent.harness.*"`.
- [ ] `./gradlew.bat :app:compileDebugKotlin` clean.
- [ ] No unbounded loops: global `maxSteps` + per-step retry caps verified by tests.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Local GGUF models produce malformed plan JSON | Heuristic fallback decomposition retained (already implemented) |
| LLM gate checks double latency/cost on-device | Off by default via `HarnessConfig.llmGateEnabled`; heuristic gate remains primary |
| VaultHelper API mismatch for VaultTools | Verify API in Phase 3 step 5 before writing wrappers |
| `android.util.Log` in tested classes | HarnessLogger abstraction (Phase 1) — do not rely on Robolectric alone |
