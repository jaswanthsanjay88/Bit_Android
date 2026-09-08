# BIT Screen-by-Screen Visual Navigation Guide

> **Official Testing & UI Reference Guide for BIT (Binary Intelligent Terminal)**  
> **Release:** v2.1.1 (Build 82)  
> **Platform:** Android 10+ (API 29+) | ARM64 NEON & x86_64  
> **Distribution:** Google Play Closed Testing Track  

---

## Closed Testing Onboarding

Authorized testers receive an invitation link to the **Google Play Closed Testing track**.  
1. Tap the invitation link on your Android device to join the program.  
2. Install **BIT (v2.1.1, Build 82)** directly from the Google Play Store.  
3. Onboarding model selection provides **LFM2 350M** (ultra-lightweight, ~400 MB) or **Gemma 3 1B IT** (~750 MB) for rapid first-run setup.  
4. Once downloaded, put the device in **Airplane Mode** to verify 100% offline local inference.

---

## Screen 1: Main Chat Interface & Local Streaming Inference

**Location:** Bottom Navigation > Chat  
**Asset:** `img/live_captures/1_chat.png`

### UI Points & Controls Explained
1. **Model Selector Pill (Top Center):** Displays active model state (e.g. `Ready: gpt-oss-20b`). Tap to open quick-switch modal without leaving the conversation.
2. **Navigation Drawer (Top Left):** Slide-out drawer with session history, full-text search, "New Chat" button, Memory Vault, and Settings.
3. **Subsystem Status Chips:** Live status chips indicating active subsystems: `RAG` (vector search active), `Tools` (device functions ready), `Memory` (episodic recall enabled), `TTS` (offline audio ready).
4. **Thinking Mode Toggle (Top Right):** Expandable `<think>` scratchpad toggle for reasoning models (DeepSeek R1, QwQ).
5. **Markdown & LaTeX Stream:** Real-time token stream rendering syntax-highlighted code blocks, copy actions, tables, and KaTeX math formulas.
6. **Message Action Sheet (Long-Press):** Bottom sheet with actions to Copy Markdown, Share via Android Sharesheet, Edit prompt, Replay aloud via Piper TTS, or Save Fact to Vault.
7. **Live Generation Metrics Bar:** Displays tokens per second (`tok/s`), Time-to-First-Token (`TTFT`), and total token counts.
8. **Attachment & Vision (+ Button):** Camera and gallery attachment picker for multimodal Vision-Language Models (VLM).
9. **Prompt Input Field:** Auto-expanding multi-line text field supporting hardware keyboards and clipboard paste.
10. **Send / Abort Streaming Button:** Dynamic button showing send arrow when idle, morphing into square to halt token generation mid-stream.
11. **Floating Liquid Voice Orb (FAB):** Animated orb above the input bar for one-tap transition into hands-free Live Voice Mode.

### Interactions & Gestures
| Gesture | Target | Result |
| :--- | :--- | :--- |
| Single Tap | Model Pill (Header) | Opens model selector dropdown with memory usage stats. |
| Long Press | Assistant Message Bubble | Opens Message Action Bottom Sheet (Copy, TTS, Share, Save). |
| Single Tap | Floating Voice Orb | Switches to full-screen Hands-Free Conversational Voice Mode. |
| Single Tap | Stop Icon (Generating) | Immediately halts llama.cpp inference loop; preserves tokens. |
| Swipe Right | Left Screen Edge | Draws out conversation session drawer. |

### Closed Testing Pass Criteria
With Airplane Mode enabled, enter *"List 3 advantages of local on-device AI"*. Tokens must stream smoothly without lag. Metrics counter must display tokens/sec upon completion.

---

## Screen 2: Live Hands-Free Conversational Voice Mode

**Location:** Chat Screen > Tap Floating Voice Orb  
**Asset:** `img/live_captures/2_voice.png`

### UI Points & Controls Explained
1. **Pulsing Liquid Neural Orb (Center):** 3D shader reflecting AI states: Cyan/Blue = Listening, Silver/White = Thinking, Warm Amber = Speaking.
2. **Live Audio Frequency Waveform:** Real-time visualizer showing 16kHz microphone capture amplitude.
3. **Zero Push-to-Talk Silero VAD:** Continuous voice activity detection triggering inference automatically when user stops speaking.
4. **Whisper ASR Streaming Transcription:** Live speech-to-text preview as words are spoken.
5. **Real-Time Voice Synthesis (Piper TTS):** On-device VITS neural voice playback with sub-300ms Time-to-First-Audio.
6. **Dynamic Latency Counter:** Header badge reporting end-to-end turn latency (ASR + LLM + TTS) in milliseconds.
7. **Microphone Mute Action:** Bottom-left circular button to pause audio capture for privacy.
8. **Barge-In & Instant Interrupt:** Speak aloud while assistant speaks; audio immediately halts and pivots to new question.
9. **Return to Chat View (X Button):** Exits voice mode; persists spoken conversation into text chat history.

### Interactions & Gestures
| Gesture | Target | Result |
| :--- | :--- | :--- |
| Speak Aloud | Hands-Free | Silero VAD detects voice; waveform animates; Whisper transcribes. |
| Speak while AI talks | Mic Audio | Barge-in triggers: TTS output immediately halts; new input processed. |
| Single Tap | Mute Button | Toggles microphone recording on/off for privacy. |
| Single Tap | Close Icon (X) | Exits Voice Mode; persists transcript into chat history. |

### Closed Testing Pass Criteria
Launch Voice Mode. Speak *"Tell me a short poem."* Once audio playback starts, interrupt by saying *"Stop, what is 2 plus 2?"*. Audio must immediately cut and answer *"4"* within 800ms.

---

## Screen 3: Model Store & GGUF Weight Manager

**Location:** Bottom Navigation > Store  
**Asset:** `img/live_captures/3_store.png`

### UI Points & Controls Explained
1. **Category Filter Chips (Top Bar):** Filter by size and task: All, Recommended, 350M–1B, 3B–7B, Vision, Tool Calling.
2. **Model Catalog Search Bar:** Instant keyword search across architecture, author, and quantization.
3. **GGUF Model Cards & Specs:** Parameters, context window limits, quantization type (`Q4_K_M`, `IQ4_XS`), and file size.
4. **Dynamic RAM Safety Badge:** Memory check against device physical RAM: Green (Safe), Amber (Heavy), Red (Insufficient).
5. **One-Tap Resumable Download:** Streams weights directly from HuggingFace with HTTP Range header support.
6. **Persistent Background Service:** Foreground notification with MB/s, percentage, and ETA when BIT is minimized.
7. **Model Activation Button:** Memory-maps weights into RAM via `mmap` as active engine.
8. **Import External Model (SAF):** Android Storage Access Framework picker for `.gguf` files from external storage or PC transfers.
9. **Delete & Storage Reclamation:** Trash icon on installed cards to immediately reclaim device storage.

### Closed Testing Pass Criteria
Download a model. Lock the device screen for 15 seconds during download. Unlock and verify download progress advanced without interruption.

---

## Screen 4: Private Document Search (RAG) & AI Memory Vault

**Location:** Bottom Navigation > Documents & Memory  
**Asset:** `img/live_captures/4_rag.png`

### UI Points & Controls Explained
1. **Dual Mode Switcher:** Toggle between Documents (knowledge base) and AI Memory Vault (episodic memory).
2. **Master Memory Toggle Switch:** Enables or disables automatic memory grounding in active chats.
3. **Vault Backup & Export Button:** One-tap button to export an encrypted backup of the vector database.
4. **Document Ingestion (+ Button):** Local file selector (.pdf, .txt, .md, .docx) parsed on-device via Apache PDFBox.
5. **Local Chunking & Vector Embeddings:** Splits text into 512-token chunks, embedded via on-device MiniLM model.
6. **Document Knowledge Base List:** Displays indexed documents with chunk count, file size, date, and delete actions.
7. **Natural Language RAG Query Bar:** Search across documents using hybrid vector cosine + BM25 keyword retrieval.
8. **Grounded Citation Badges:** Source chip (e.g. `[Report.pdf - Page 4]`) linking directly to the source text chunk.

### Closed Testing Pass Criteria
Import a 2-page PDF document. Query a detail from page 2. Verify answer contains an exact quotation and source badge pointing to page 2.

---

## Screen 5: Model Parameter Configuration & Sampler Tuner

**Location:** Model Store > Tap Model > Config Editor  
**Asset:** `img/live_captures/5_editor.png`

### UI Points & Controls Explained
1. **Model Header & Metadata Banner:** Displays active model name, architecture family, quantization, and context limits.
2. **Context Length Slider (n_ctx):** Controls context buffer allocation from 1,024 to 32,768 tokens.
3. **Temperature Slider (0.0 to 1.5):** 0.0 = Pure greedy deterministic; 0.7 = Conversational; 1.2+ = Creative.
4. **Top-P (Nucleus Sampling) Control:** Restricts candidate tokens to cumulative probability P (default: 0.9).
5. **Top-K Sampling Control:** Limits token pool to top K highest-probability tokens (default: 40).
6. **Repetition & Frequency Penalty:** Penalizes repeated tokens (1.0 to 1.5) to prevent repetitive loops.
7. **System Persona Prompt Editor:** Custom system prompt applied across all conversation turns.
8. **GBNF Grammar Schema Selector:** Context-free grammars for strict JSON object generation and tool routing.
9. **GPU Offload Layers (n_gpu_layers):** Number of transformer layers offloaded to mobile GPU shaders via Vulkan/OpenCL.

### Closed Testing Pass Criteria
Set Temperature to 0.0. Ask *"What is 256 + 144?"*. Repeat query 3 times. Verify output is 100% deterministic across all runs with zero variance.

---

## Screen 6: Hardware Diagnostics & Settings

**Location:** Bottom Navigation > Settings  
**Asset:** `img/live_captures/6_settings.png`

### UI Points & Controls Explained
1. **Diagnostics Header & Share Action:** Consolidated diagnostic zip bundle export for bug triage.
2. **Hardware Overview Card:** Detected SoC, CPU core cluster topology, and SIMD instruction sets (NEON, KleidiAI).
3. **Real-Time RAM Pressure Gauge:** Displays Total Device RAM, Free Memory, and BIT Memory Footprint.
4. **CPU Thread Allocation Slider:** Adjust thread count (2, 4, 6, 8 threads; 4 recommended for octa-core chips).
5. **SIMD Hardware Acceleration Toggles:** Status badges for Arm KleidiAI acceleration and OpenCL/Vulkan shaders.
6. **Offline Speech Engine Settings:** Select neural voices for Piper TTS; adjust pitch and tempo.
7. **Tool Calling & Router Model Settings:** Toggle primary model tool routing vs secondary routing model.
8. **Zero-Telemetry Security Verification:** Real-time socket monitor confirming 0 outbound network requests.

### Closed Testing Pass Criteria
Open Diagnostics. Verify detected physical RAM matches phone specifications. Adjust thread count from 4 to 2; verify settings persist upon app relaunch.
