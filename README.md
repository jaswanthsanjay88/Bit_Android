# BIT — Android Offline AI Client Powered by llama.kt

BIT is a fully offline, privacy-first AI assistant client for Android. Built entirely on top of the llama.kt SDK, BIT enables running Large Language Models (LLMs), Vision-Language Models (VLMs), on-device image generation, text-to-speech, and secure local Document Retrieval (RAG) without external servers, subscriptions, or data leaving the device.

---

## Powered by the llama.kt SDK

At the core of the BIT client is the llama.kt SDK. Located in the llama-kt module, this SDK functions as a unified native bridge between high-performance C++ inference backends and the Android Kotlin runtime. 

The SDK encapsulates:
- Native llama.cpp Bindings: Custom JNI bindings that execute local inference for GGUF formatted models with optimized memory mappings and low-latency token streaming.
- Context and KV Cache Management: Programmatic sliding window eviction, prompt estimation, and memory management through native pointers.
- Multimodal Vision (VLM) Projector Support: Loading clip projectors alongside text models to perform on-device image description and visual understanding.
- Logit Bias and Custom Mood Samplers: Granular temperature, top-k, top-p, and min-p configuration overrides passed directly to the native sampler stack.

---

## Application Architecture

The project is structured as a modularized Android application to isolate native engine operations from the presentation layer:

- app: The main application module containing the Jetpack Compose user interface, ViewModels, navigation, and application state.
- llama-kt: The core Kotlin SDK wrapping the native llama.cpp library and JNI bindings.
- ums: User Management System and secure session management.
- neuron-packet: Parser and manager for local encrypted RAG packets (.neuron).
- system_encryptor: Cryptographic utilities utilizing AES-256-GCM backed by the Android Keystore System.
- file_ops: On-device file management and backup utilities.
- memory-vault: Storage engine that manages the long-term episodic memory of the assistant.

---

## Key Features

- On-Device Text Generation: Powered by llama.kt, the application runs modern GGUF models (such as Llama, Mistral, Gemma, Phi, Qwen) with native hardware acceleration.
- Visual Chat (VLM): Analyze images locally by loading clip projector models through the VLM implementation in the SDK.
- Tool Calling: Local tool execution allowing the LLM to interact with the device (performing system checks, calculations, notepad reads, and local file operations). Detailed tools and parameters are defined and explained inside the Application.
- On-Device Image Generation: Build images using Stable Diffusion 1.5 with support for inpainting and local upscaling.
- Document RAG: Query local PDF, Word, Excel, EPUB, and TXT files using hybrid retrieval (vector search combined with FTS4 BM25).
- Long-Term AI Memory: Episodic memory extraction and storage with decay curves, allowing the assistant to retain facts across chat sessions.
- On-Device Text-to-Speech: Native speech synthesis using ONNX Runtime with multiple voices and language configurations.
- Secure Backups: Encrypt and export chat history, memories, and model settings to a local .tnbackup file.

---

## Requirements

| Metric | Minimum | Recommended |
|---|---|---|
| Android OS | Android 10 (API 29) | Android 12 (API 31) or higher |
| Device RAM | 6 GB | 8 GB to 12 GB |
| Free Storage | 4 GB | 10 GB or higher |
| Processor | ARM64 or x86_64 | Snapdragon 8 Gen 1 or higher |

---

## Getting Started

### 1. Installation
Install the application using the pre-compiled APK binaries available in the GitHub Releases section.

### 2. Loading a Model
Models can be acquired in two ways:
- In-App Model Store: Open the drawer menu, go to the Model Store, register a Hugging Face repository, and download a quantized GGUF file.
- Manual Import: Download a GGUF model manually to your device storage and use the model picker inside the application to reference it.

---

## Build Instructions

To build the project locally, ensure you have the Android SDK, NDK, and CMake installed.

### 1. Clone the Repository
Clone the repository recursively to fetch the llama.kt submodule:
```bash
git clone --recursive https://github.com/jaswanthsanjay88/Bit_Android.git
cd Bit_Android
```

### 2. Configure Submodules
If cloned without recursive flags, initialize the submodules manually:
```bash
git submodule update --init --recursive
```

### 3. Build Binaries
Use the Gradle wrapper to build the application. 

Build a debug package:
```bash
./gradlew assembleDebug
```

Build signed release packages:
```bash
./gradlew assembleRelease
```
The release build is configured to split compilation outputs by target architecture (Universal, ARM64, and x86_64) to optimize file sizes. The output files are saved in the app/build/outputs/apk/release/ directory.

---

## License

This project is licensed under the Apache License 2.0. See the LICENSE file or visit the Apache License 2.0 page (https://www.apache.org/licenses/LICENSE-2.0) for detailed licensing terms.
