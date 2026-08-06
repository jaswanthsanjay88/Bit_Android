# BIT Quality Assurance & Testing Guide

This document outlines the testing protocols for BIT releases. Testers should execute these cases on physical Android devices to ensure stability, particularly for memory-intensive operations like loading GGUF models.

## Pre-requisites
- **Test Device**: Android 12+ (API 31+) device, preferably with 8GB+ RAM.
- **Tools**: `adb` access to the device for capturing logs.

---

## Test Suite: Release Build Verification

### 1. Installation & Environment Verification
1. **Action**: Run `adb install -r app-arm64-v8a-release.apk`
2. **Action**: Open the app and observe the launch sequence.
3. **Expected**: App launches smoothly to the Chat Interface. No crashing on the splash screen.
4. **Action**: Navigate to `Settings -> Diagnostics`.
5. **Expected**: CPU architecture (ARM64), Memory, and NPU availability should be accurately reported.

### 2. GGUF Model Loading (Local Engine)
1. **Action**: Open the **Model Store**.
2. **Action**: Locate `all-MiniLM-L6-v2-Q5_K_M.gguf` or similar lightweight model and tap **Download**.
3. **Expected**: Download progress bar should update without UI freezes and complete to 100%.
4. **Action**: Tap **Import Local Model** and select any GGUF file via the Android Storage Picker (SAF).
5. **Expected**: The file should be copied into internal storage and immediately appear in the loaded model list.
6. **Action**: Select the model and generate a prompt: *"Hello, how are you today?"*
7. **Expected**: Text streams dynamically. `LlmModelWorker` notification should appear in the system tray indicating the engine is pinned.

### 3. API Model Fallback & Configuration
1. **Action**: Navigate to **Model Store -> Providers**.
2. **Action**: Add an API key for OpenAI, Gemini, or Claude.
3. **Action**: Select an API model as the Active Model.
4. **Expected**: The model should be flagged as Active.
5. **Action**: Return to chat and send a prompt.
6. **Expected**: The chat should stream the response. Token metrics (tokens/sec) should be displayed below the message.
7. **Action**: Force kill the app from the Recents menu. Restart the app.
8. **Expected**: The API model should automatically re-mount without throwing a timeout error.

### 4. Backgrounding & Persistence
1. **Action**: Start a text generation request with a local model.
2. **Action**: Press the Home button to minimize the app mid-generation.
3. **Expected**: The `LlmModelWorker` foreground service notification should remain active.
4. **Action**: Re-open the app after 30 seconds.
5. **Expected**: The UI should seamlessly reconnect to the ongoing generation or display the finished result without crashing or reloading the engine.

### 5. Memory Vault & RAG
1. **Action**: Send the prompt: *"Remember that my favorite programming language is Kotlin."*
2. **Action**: Open the **Memory Vault** tab.
3. **Expected**: A new memory note entry should be present in the `ai_memory` folder.
4. **Action**: Swipe or tap to delete the memory entry.
5. **Expected**: The entry should disappear from the UI immediately.
6. **Action**: Ask the AI: *"What is my favorite programming language?"*
7. **Expected**: The AI should state it doesn't know, confirming the memory was purged.

### 6. Live Voice Mode & Barge-in
1. **Action**: Tap the microphone icon. Grant necessary permissions.
2. **Action**: Speak clearly: *"Tell me a long story about space."*
3. **Expected**: Speech is transcribed in real-time.
4. **Action**: While the TTS is reading the response aloud, start speaking.
5. **Expected**: The Voice Activity Detection (VAD) should immediately pause the TTS output and switch back to listening mode (barge-in).

---

## Log Capturing
If any crashes or bugs occur, please capture the stack trace via adb:
```bash
adb logcat -s "AndroidRuntime:E" "EmbeddingEngine:V" "GlobalRagOrchestrator:V" "LlmModelWorker:V"
```
Attach the logs to your GitHub Issue.
