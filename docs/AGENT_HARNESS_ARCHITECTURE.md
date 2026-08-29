# BIT Agent Harness — Architecture Reference

> Implementation map for the on-device multi-agent harness (`com.bit.agent.harness`), from UI to LLM execution.
> Line anchors verified at current head. Regenerate anchors after large refactors.

---

## 1) Agent harness (core orchestration)

Two engines behind one entry router:

- `app/src/main/java/com/bit/viewmodel/ChatViewModel.kt`
  - `sendChat()` (L874): prompts starting with `/goal` route to `startAgentGoal()` (L164); everything else runs the unified multi-turn tool loop (L1508, `maxRounds = 256` at L1493).
  - Owns UI projection: `thinkingLog`, `StepEvent` emission (`emitStepEvent` L512), grouped `AgentState` (L600 area).

- `app/src/main/java/com/bit/agent/harness/engine/AgentHarnessEngine.kt` — the `/goal` FSM:
  - `executeGoal()` (L102): sequential step loop; global budget `DEFAULT_MAX_STEPS = 256`, `DEFAULT_MAX_RETRIES_PER_STEP = 3`.
  - State machine (`state/AgentHarnessState.kt`): `Idle → Decomposing → Executing → GateChecking → SelfCorrecting → Completed | Failed`.
  - Step order decided by:
    1. `LlmGoalPlanner` (`engine/PlanGenerator.kt`, `generatePlanJson` L42) — asks the active model for a JSON step array; parsed by `parsePlanJson()` (L391).
    2. Heuristic fallback `decomposeGoal()` (L466) — keyword branches, `extractSearchQuery()` (L591), reviewer detection.
  - Human-in-the-loop suspension: `pendingApprovals` / `pendingAnswers` CompletableDeferreds; public API `approveStep()` (L68), `denyStep()` (L74), `answerPendingQuestion()` (L87); 5-min timeout (`APPROVAL_TIMEOUT_MS` L51).
  - Per-step pipeline: subagent payload unwrap (L293) → `ToolOutputTruncator` → `gateChecker.verify()` (L308) → `correctionPlanner.planCorrection()` (L325) → next step or `synthesizeResults()` (L608).

Plan steps are `TaskStep(id, description, toolName, toolArguments, expectedOutcome, status, observation, retryCount)` — mutable, gate-checked, honestly marked (`PASSED` only when actually executed).

## 2) Multi-agent implementation

- `engine/SubagentRunner.kt` — real isolated agent loop:
  - Own message history + role/mission system prompt (`buildSystemPrompt()` L310, includes recommended JSON claims block).
  - Toolset = `PluginManager` enabled tools **minus** `invoke_subagent` (recursion blocked).
  - Per round: stream → execute tool calls → append tool/result `ChatMessage` pairs.
  - Recovery: text-only rounds shorter than `MIN_FINAL_ANSWER_CHARS` (L39) are treated as chatter and nudged (L110–130); all-filtered rounds get a "write final report" nudge (L175); budget exhaustion triggers tools-disabled forced synthesis (L191).
  - Output: `stripThinking()` (L281) → `ClaimExtractor.extract()` (L217) → `SubagentResult`.
- `tools/SubagentTools.kt` — `SubagentExecutor` interface (L17) + `InvokeSubagentTool` (L24, schema: `role`, `goal`, `max_steps≤20`); wraps result into `ToolObservation` incl. `claims[]`.
- `tools/AgentToolRegistry.kt` — cached action space (`tools()` L40); `subagentExecutor` slot (L37) wired by `di/HarnessModule.kt` (L70).
- Context pipelining: `buildSubagentGoal()` (engine L570) appends `=== FINDINGS FROM PRIOR STEPS ===` from prior step observations, capped at `MAX_SUBAGENT_CONTEXT_CHARS` (6,000, L52).

## 3) Context/prompt transfer path (UI → model)

1. **UI composition** — `ui/screen/home/BodyContent.kt`: collects `agentState` + `pendingApproval` (L243); renders live trace (L395), `AgentQuestionCard` (L417), `AgentApprovalCard` (L423).
2. **Goal planning** — `buildPlanningPrompt()` (PlanGenerator L137): registry tool names + planning rules (L167: multi-part goals, reviewer steps, clean queries, no invented years) → JSON array → `parsePlanJson()`.
3. **Per-step context** — `step.toolArguments` executed via registry/bridge; observation stored on step; pipelined into write/memory tools (engine L172 area) and subagent goals (L570).
4. **LLM message assembly** — chat loop: `buildConversationMessagesWithSteps(fullPrompt, steps, …)`; remote path `generateRemoteUnified()` (L1826) converts each `ToolChainStepData` into a `ChatMessage(toolCall = ToolCallData)` pair (`tool_`/`result_` id prefixes, `com.bit.api.util.buildToolCallId`); RAG context prepended to `fullPrompt`.
5. **Transport contract** — `api/LlmProvider.kt`: `ProviderConfig(apiKey, modelId, systemPrompt, baseUrl, tools, …)` → `LlmProvider.generateResponse(): Flow<StreamEvent>` (`TextChunk`, `ThoughtChunk`, `ToolCallRequest(s)`, `Retrying`, `Error`).

Context flow is explicit, never broadcast: **prompt + tool schemas in `ProviderConfig` → streamed events → executed observations → appended back as message pairs**.

## 4) How agents get info

1. **Main agent**: `ProviderConfig.tools` (from `PluginManager.getEnabledToolDefinitions()` L381), system prompt, RAG context, history + tool/result pairs.
2. **Subagent**: isolated history seeded only with role/mission prompt + piped findings — cannot see parent chat (by design).
3. **User → agent**: `ask_user` suspends the FSM (engine L212–247); typed answer becomes the tool observation payload (`ToolApprovalState.Answered`).
4. **Tool layer contract**: `tools/AgentTool.kt` (`definition`, `requiresApproval`, `needsApproval()`, `execute(argsJson): ToolObservation`); plugin tools bridged via `tools/AgentToolBridge.kt` → `PluginManager.executeToolForMultiTurn()` (L416); MCP tools via `mcp/McpManager` (+ `McpOAuthClient`/`McpOAuthCoordinator`).

## 5) Use cases (UI → code)

| Use case | Path |
|---|---|
| Autonomous goal | `/goal X` → `startAgentGoal` (L164) → FSM → `persistAgentChat` |
| Chat with tools | `sendChat` (L874) → unified loop (L1508) → `PluginManager` (L1581) → synthesis pass (L1655) if empty text |
| Dangerous tool | `requiresApproval=true` (shell, clipboard) → `AwaitingApproval` → `AgentApprovalCard` → `approveStep/denyStep` |
| Clarification | `ask_user` → `AgentQuestionCard` → `answerPendingQuestion` |
| Verification | planner/heuristic emits `invoke_subagent` → findings piped (L570) → isolated loop → report + `claims[]` |
| Failed step | gate fail (L308) → `planCorrection` (L325) → retry ≤3 → `Failed(partialArtifacts)` |

## 6) Files to read first

1. `agent/harness/engine/AgentHarnessEngine.kt` — FSM, budgets, approvals, pipelining
2. `agent/harness/engine/SubagentRunner.kt` — multi-agent loop + recovery
3. `viewmodel/ChatViewModel.kt` — routing, unified loop, state projection (god-class: ~4k lines)
4. `agent/harness/state/AgentHarnessState.kt` + `agent/harness/model/ToolObservation.kt` — all contracts
5. `agent/harness/tools/AgentToolRegistry.kt` + `tools/AgentTool.kt` — action space
6. `agent/harness/engine/PlanGenerator.kt` — LLM planning prompt
7. `api/LlmProvider.kt` + `api/ChatMessage.kt` — transport contracts
8. `agent/harness/di/HarnessModule.kt` — DI wiring
9. `ui/components/AgentHarnessThinkingView.kt` — approval/question cards
10. `plugins/PluginManager.kt` — tool execution substrate

---

## 7) Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant U as User (Chat UI)
    participant VM as ChatViewModel
    participant E as AgentHarnessEngine
    participant P as PlanGenerator (LLM)
    participant S as SubagentRunner
    participant PM as PluginManager (tools)
    participant L as LlmProvider (remote API)

    U->>VM: "/goal research X, then reviewer verifies"
    VM->>E: executeGoal(goal)  [flow, Dispatchers.Default]
    E->>P: generatePlanJson(goal)
    P->>L: planning prompt (tools + rules)
    L-->>P: JSON step array
    P-->>E: TaskPlan(steps)

    loop each step (turnCount < 256)
        alt step.toolName == invoke_subagent
            E->>E: buildSubagentGoal() — pipe prior findings (≤6k chars)
            E-->>VM: SubagentRunning(role, parentStep)
            VM->>VM: thinkingLog += "Deploying subagent…"; StepEvent(SUBAGENT)
            E->>S: SubagentTask(role, goal+piped findings, maxSteps)
            loop isolated rounds (≤ maxSteps)
                S->>L: generateResponse(history, tools-minus-subagent)
                L-->>S: TextChunk / ToolCallRequest(s)
                alt tool calls present
                    S->>PM: executeToolForMultiTurn(call)
                    PM-->>S: MultiTurnToolResult
                    S->>S: append tool/result ChatMessage pair
                else text-only < 100 chars
                    S->>S: nudge "continue or write final report"
                end
            end
            opt budget exhausted mid-work
                S->>L: forced synthesis (tools = null)
            end
            S-->>E: SubagentResult(output, claims[], artifacts)
            E->>E: unwrap payload → clean text
        else registeredTool.needsApproval(args)
            E-->>VM: AwaitingApproval(step, tool, args)
            VM-->>U: AgentApprovalCard / AgentQuestionCard
            alt user approves
                U->>VM: approvePendingAgentStep()
                VM->>E: approveStep(stepId) → deferred.complete(true)
                E->>PM: registeredTool.execute(args)
            else user denies / 5-min timeout
                U->>VM: denyPendingAgentStep()
                VM->>E: denyStep(stepId) → deferred.complete(false)
                E->>E: observation = ERROR("denied by user")
            end
        else normal tool
            E->>PM: execute via registry / bridge
            PM-->>E: ToolObservation
        end

        E->>E: truncate output → gateChecker.verify(step, obs)
        E-->>VM: GateChecking(step, obs, passed)
        VM->>VM: StepEvent(GATE, durationMs, success)

        alt gate failed && retries left
            E->>E: planCorrection() → revised args / alt tool
            E-->>VM: SelfCorrecting(retryCount)
        else gate failed, retries exhausted
            E-->>VM: Failed(reason, partialArtifacts)
        end
    end

    E->>E: synthesizeResults(goal, plan)
    E-->>VM: Completed(finalResult, artifacts, totalTurns, timeMs)
    VM-->>U: summary + persisted trace cards
```

## 8) Exact delegation & suspension anchors (current head)

| What | File | Line |
|---|---|---|
| `/goal` routing | `ChatViewModel.kt` | L874 (`sendChat`) |
| FSM entry | `ChatViewModel.kt` / `AgentHarnessEngine.kt` | L164 / L102 |
| LLM planning | `PlanGenerator.kt` | L42, L137 |
| Heuristic planning | `AgentHarnessEngine.kt` | L466, L591 |
| Subagent delegation | `AgentHarnessEngine.kt` | L139 → L570 (pipe) → L162 (state) |
| Subagent loop start | `SubagentRunner.kt` | L42 |
| Chatter nudge | `SubagentRunner.kt` | L110–130 |
| Filtered-call nudge | `SubagentRunner.kt` | L175 |
| Forced synthesis | `SubagentRunner.kt` | L191 |
| Claims extraction | `SubagentRunner.kt` / `ClaimExtractor.kt` | L217 / L19 |
| Approval suspension | `AgentHarnessEngine.kt` | L213–283 (timeout L51) |
| ask_user suspension | `AgentHarnessEngine.kt` | L212–247 |
| Approve/Deny/Answer API | `AgentHarnessEngine.kt` | L68 / L74 / L87 |
| UI approval entry | `ChatViewModel.kt` / `BodyContent.kt` | L135–160 / L243, L417, L423 |
| Gate check | `AgentHarnessEngine.kt` | L308 |
| Self-correction | `AgentHarnessEngine.kt` | L325 |
| Chat tool execution | `ChatViewModel.kt` | L1581 (dedup cap ~L1520) |
| Chat synthesis pass | `ChatViewModel.kt` | L1655 |
| Remote message assembly | `ChatViewModel.kt` | L1826 |
| StepEvent emission | `ChatViewModel.kt` | L512 (+ collector L172–345) |
| DI subagent wiring | `HarnessModule.kt` | L70 |
| Contracts | `ToolObservation.kt` / `StepEvent.kt` / `AgentState` | L146/L163 / L7 / L33 |
