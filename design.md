# BIT App - Design Document

## 1. Overview
BIT is an Android application that provides intelligent background automation and natural language-driven tasks. The app leverages local on-device models (GGUF) and a robust tool execution pipeline to fetch live data (e.g., news, stocks, sports) and notify the user.

## 2. Architecture
- **UI Framework:** Jetpack Compose (Material Design 3)
- **Local Database:** Room Database (`AppDatabase`, Version 20+)
  - Key Entities: `TaskEntity`, `TaskRunEntity`, `ChatEntity`, `MessageEntity`
- **Background Execution:** WorkManager (`TaskWorker`) and AlarmManager (Scheduled Tasks)
- **Local LLM Engine:** GGUF Engine with streaming inference and GBNF grammar constraints.

## 3. Core Components

### 3.1. TwoStageToolRouter
The `TwoStageToolRouter` manages the interaction between the local LLM and executable tools.
- **Stage 1 (Tool Selection):** Evaluates the user's prompt and selects the most appropriate tool (e.g., `WebSearchPlugin`, `RSS`, etc.).
- **Stage 2 (Argument Generation):** Generates strict JSON arguments for the selected tool using GBNF grammar.
- **Execution:** The `PluginManager` executes the selected tool.

### 3.2. AgentLoopExecutor
Runs in the background (via `TaskWorker`) to evaluate long-running "watchers" or scheduled tasks.
- Takes the raw JSON result from `TwoStageToolRouter`.
- Uses a secondary LLM synthesis pass to convert raw tool data into 1-3 crisp plaintext lines (e.g., "India 240/4 (38.2 ov) · Kohli 84*" or 3 bulleted news headlines).
- Compares new outputs against historical runs using `ListDedupComparator` and `ScalarDiffComparator` to detect changes and trigger notifications.

### 3.3. Automation UI (Minimalist Design)
The UI heavily favors a minimalist, no-flourish aesthetic.
- **WatcherRow:** Displays active automation tasks with contextual subtitles (e.g., "Every 5 minutes with new stories"). 
- **CadenceIndicator:** Visual tick markers denoting the frequency of the task.
- **Interactions:** Avoids complex swipe gestures in favor of clear, explicit icon buttons (e.g., a trailing `X` icon for deletion).

## 4. Database Schema Rules
- Schema migrations must precisely align SQLite `ALTER TABLE` defaults with Kotlin `@ColumnInfo(defaultValue = ...)` annotations to avoid Room verification crashes.
- `task_runs` are strictly tied to `tasks` via `FOREIGN KEY ... ON DELETE CASCADE`.

## 5. Future Considerations
- Expand plugin ecosystem for more specialized data retrieval tools.
- Optimize battery usage for frequent delta-triggered background tasks.
