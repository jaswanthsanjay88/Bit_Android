# Bit

Bit is an offline-first, on-device AI toolkit for Android.

## Maintained by
- GitHub: @jaswanthsanjay88

## What It Does
- **Text generation** — Load any GGUF model (Llama, Mistral, Gemma, Phi, Qwen, etc.) and chat with it locally
- **Image generation** — Stable Diffusion 1.5 on-device, with inpainting support
- **Image tools** — Upscale and segment images locally (depth, style transfer, inpainting coming soon)
- **RAG** — Inject PDFs, Word docs, Excel, EPUB into conversations with semantic search
- **Plugins** — Web search, file manager, calculator, notepad, date/time, system info, dev utils — all callable by the LLM
- **AI memory** — The AI remembers facts about you across conversations, with deduplication and a forgetting curve
- **Text-to-speech** — 10 voices, 5 languages, on-device synthesis
- **Encrypted storage** — AES-256-GCM with hardware-backed keys for all chat data
- **System backup** — Export everything as an encrypted .tnbackup file

## Requirements

| | Minimum | Recommended |
|---|---:|---:|
| **Android** | 10 (API 29) | 12+ |
| **RAM** | 6 GB | 8–12 GB |
| **Storage** | 4 GB free | 10 GB free |
| **CPU** | ARM64 or x86_64 | Snapdragon 8 Gen 1+ |

## Getting Started

### 1) Install
Install from Google Play or GitHub Releases.

### 2) Get a model
**From the in-app Model Store (recommended):**
- Open the drawer menu → **Model Store**
- Add a HuggingFace repository (e.g. `bartowski/Phi-3.5-mini-instruct-GGUF`)
- Pick a quantization and download

**Or manually:**
- Download a `.gguf` file from HuggingFace
- Use the model picker in Bit to load it

### 3) Chat
Select your model, wait for it to load, start typing. Responses stream in real-time.

## Recommended models for getting started

| Use case | Model | Size |
|---|---|---:|
| Quick test | Qwen3.5 0.8B Q4_K_M | ~600 MB |
| General use | Qwen3.5 4B Q4_K_M | ~2.8 GB |
| Power users | Qwen3.5 9B Q4_K_M | ~5.5 GB |

Pick **Q4_K_M** for a good balance between quality and size. Use **Q6_K** if your device has the RAM for it.

## Features

### Text Generation
- Any GGUF model works — load via file picker (no storage permissions needed, uses SAF)
- Configurable parameters: temperature, top-k, top-p, min-p, repeat penalty, context length
- Function calling with grammar-constrained JSON output
- Thinking mode for models that support it
- Per-model configs saved to database

### Image Generation
- Stable Diffusion 1.5 (censored and uncensored variants)
- Text-to-image and inpainting
- Configurable steps, CFG scale, seed, negative prompts, schedulers

### Image Tools

| Tool | Status |
|---|---|
| Upscaling | Ready |
| Segmentation (MobileSAM) | Ready |
| Depth estimation | Model pending |
| Style transfer | Model pending |
| LaMa inpainting | Model pending |

### RAG (Document Intelligence)
Create knowledge bases from:
- Files — PDF, Word (.doc/.docx), Excel (.xls/.xlsx), EPUB, TXT
- Text — Paste any text content
- Chat history — Convert past conversations into searchable knowledge
- Neuron Packets — Import encrypted .neuron RAG files

The RAG pipeline uses hybrid retrieval: FTS4 BM25 + vector search + Reciprocal Rank Fusion + Maximal Marginal Relevance. Results are injected into the conversation context automatically.

Encrypted RAGs support admin passwords and read-only user access.

### Plugin System
7 built-in plugins the LLM can call during conversations:

| Plugin | What it does |
|---|---|
| Web Search | Search the web and scrape content |
| File Manager | List, read, create files |
| Calculator | Math expressions and unit conversion |
| Notepad | Save and retrieve notes |
| Date & Time | Current time, timezone conversion, date math |
| System Info | RAM, battery, storage, device details |
| Dev Utils | Hash, encode, format, text transforms |

### AI Memory
Inspired by Mem0. After conversations, the LLM extracts facts about you and stores them for future context. Deduplication via Jaccard similarity, with a forgetting curve so stale memories decay. You can view, edit, and delete memories from the Memory screen.

### Text-to-Speech
On-device TTS via Supertonic (ONNX Runtime). 10 voices (5 female, 5 male), 5 languages (EN, KR, ES, PT, FR). Adjustable speed and quality. Auto-speak option reads responses aloud.

### Hardware Tuning
Auto-detects CPU topology (P-cores, E-cores) and recommends thread count, context size, and cache settings. Three modes: Performance, Balanced, Power Saver.

### System Backup
Export everything to an encrypted `.tnbackup` file (PBKDF2 + AES-256-GCM):
- Chat history, AI memories, personas, knowledge graphs
- Model configs and app settings
- RAG files and AI models (optional, can be large)

## Privacy
- Zero data collection. No telemetry, no analytics, no crash reporting.
- Everything stays on-device. Conversations, generated images, documents, TTS audio — none of it leaves your phone.
- Encrypted storage. AES-256-GCM with Android KeyStore. On supported devices, keys live in the Trusted Execution Environment.
- No storage permissions. Models load through Android's file picker (SAF). The app can't access arbitrary files.
- Open source. Read the code yourself.

## Building from Source

### Prerequisites
- Android Studio Meerkat (2025.1.1)+
- JDK 17
- Android SDK 36+, NDK 26.x

### Build
```bash
git clone https://github.com/Siddhesh2377/ToolNeuron.git
cd ToolNeuron

# Debug
./gradlew assembleDebug
./gradlew installDebug

# Release
./gradlew assembleRelease
