# llama.kt

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Android Min SDK](https://img.shields.io/badge/Android-Min%20SDK%2024-green.svg?logo=android)](https://developer.android.com)
[![Hardware Offload](https://img.shields.io/badge/Hardware%20Offload-QNN%20%2F%20NeuroPilot%20%2F%20Vulkan-red.svg)](#hardware-acceleration)
[![Vision](https://img.shields.io/badge/Vision-LLaVA%20%2F%20CLIP-purple.svg)](#multimodal-vision)
[![Audio](https://img.shields.io/badge/Audio-Whisper.cpp-blue.svg)](#on-device-voice-transcription)
[![Release Version](https://img.shields.io/badge/Release-v1.2.0-blue.svg)](https://github.com/ggml-org/llama.cpp)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)

llama.kt is a production-grade, high-performance local LLM and multimodality inference engine for Android and Kotlin, built on top of llama.cpp and fully optimized for mobile hardware accelerators.

Run Large Language Models — Llama 3, Phi-3, Gemma, Mistral, Qwen, and more — entirely on-device. **Fully private. Zero network overhead. Hardware-accelerated.**

---

## Table of Contents
- [Architecture and Core Concepts](#architecture-and-core-concepts)
- [Key Upgrades (Parity with LiteRT-LM)](#key-upgrades-parity-with-litert-lm)
- [Getting Started](#getting-started)
- [In-Depth DSL Usage Examples](#in-depth-dsl-usage-examples)
- [API Subsystems](#api-subsystems)
- [Systrace Profiling](#systrace-profiling)
- [Contributing and License](#contributing-and-license)

---

## Architecture and Core Concepts

```
                  +----------------------------------------------+
                  |                 LlamaKt DSL                  |
                  +----------------------------------------------+
                                         |
                                         v
                  +----------------------------------------------+
                  |               GGMLEngine                       |
                  +----------------------------------------------+
                     /                   |                    \
                    v                    v                     v
        +-------------------+  +-------------------+  +-------------------+
        |  CharacterEngine  |  |    RAGEngine      |  | AudioTranscriber  |
        |  (Control/Moods)  |  | (Semantic Chunks) |  |   (Whisper JNI)   |
        +-------------------+  +-------------------+  +-------------------+
                    \                    |                    /
                     v                   v                   v
                  +----------------------------------------------+
                  |             JNI Bridge (gguf_lib)            |
                  +----------------------------------------------+
                                         |
                  +----------------------------------------------+
                  |                  llama.cpp                   |
                  +----------------------------------------------+
                    /                    |                     \
                   v                     v                      v
        +------------------+   +------------------+   +------------------+
        |   Qualcomm QNN   |   | MediaTek Neuron  |   |  Vulkan/OpenCL   |
        |   Hexagon NPU    |   |  NeuroPilot APU  |   |    Adreno/Mali   |
        +------------------+   +------------------+   +------------------+
```

---

## Key Upgrades 

### 1. Hardware Acceleration (NPU, APU and GPU Offloading)
Bypasses CPU processing limits by compiling tensor instructions to dedicated accelerators:
* **Qualcomm Hexagon NPU**: Native QNN (Qualcomm Neural Network) backend integration (`libQnnHtp.so`) utilizing DSP tensor pipelines.
* **MediaTek APU**: Binds mathematical nodes directly to MediaTek NeuroPilot APUs (`libneuron_runtime.so`).
* **Mali and Adreno GPUs**: Seamless Vulkan and OpenCL compute offloading.

### 2. Speculative Target-Draft Verification
Increases processing speeds by up to **2x** on structured text (such as JSON or code blocks) using a lightweight dual-model speculative check:
* Integrates a secondary small draft model (e.g., `Llama-3.2-1B-Draft`) alongside the target model.
* The draft model predicts a sequence of token candidates, which are verified in parallel batches by the target model inside C++ JNI routines.

### 3. Dynamic RAM Swapping (Preventing OOM Crashes)
Prevents background memory closures by actively releasing unused resources:
* **`purgeRAM()`**: Employs Linux kernel page eviction (`madvise(MADV_DONTNEED)`) on the memory-mapped GGUF model weights, purging active resident RAM usage to zero when the app moves to the background.
* **`reloadRAM()`**: Runs a fast model weight fault-in warm-up pass before generation begins, ensuring instant response.

### 4. Dynamic Context Sliding KV Cache Windows
Eliminates context overflow crashes for long-running infinite chats:
* Uses native `llama_memory_seq_rm` and `llama_memory_seq_shift` to evict older conversational tokens while leaving system prompts fully intact.
* Exposed cleanly in both low-level and high-level DSL APIs (`applySlidingWindow(windowSize)`).

### 5. Type-Safe JSON Schema Constrained Generation
Generates structured JSON data conforming directly to Kotlin class definitions:
* Kotlin reflection parses data fields and automatically generates strict JSON grammar constraints.
* Enforces output validation through JNI grammar rules, automatically deserializing outputs back into type-safe Kotlin objects using GSON.

---

## Getting Started

### Installation
Add the module to your Android Gradle configuration:

```kotlin
// settings.gradle.kts
include(":llama.kt")

// app/build.gradle.kts
dependencies {
    implementation(project(":llama.kt"))
    implementation("com.google.code.gson:gson:2.10.1")
}
```

---

## In-Depth DSL Usage Examples

### 1. Basic Generation with Device-Adaptive Recommender
Resilience-oriented model downloading and adaptive loading based on detected device RAM:

```kotlin
// Determine device performance tier (Low, Mid, High)
val downloader = ModelDownloader(context)
val recommendation = downloader.getRecommendation()

// Download progressive model
downloader.download(recommendation.url, recommendation.name, object : ModelDownloader.DownloadCallback {
    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, percentage: Float, speedBytesPerSec: Long) {
        println("Downloading: $percentage% at ${speedBytesPerSec / 1024} KB/s")
    }
    override fun onComplete(file: File) {
        // Run hardware accelerated inference
        val llama = LlamaKt {
            model(file.absolutePath)
            contextSize(4096)
            backend(InferenceBackend.GPU_VULKAN) // Or NPU_QNN / CPU
            flashAttention(true)
        }

        CoroutineScope(Dispatchers.Main).launch {
            llama.chat("Explain quantum physics simply.").collect { event ->
                if (event is GenerationEvent.Token) print(event.text)
            }
        }
    }
    override fun onError(message: String) {
        System.err.println("Download failed: $message")
    }
})
```

### 2. Type-Safe Structured Serialization
Dynamically generates JSON schemas using reflection and returns strongly-typed Kotlin classes:

```kotlin
data class FilmReview(val title: String, val rating: Int, val isWorthWatching: Boolean)

// The model output is strictly constrained to the schema structure, deserialized on-the-fly
val review: FilmReview? = llama.generateTypeSafe(
    prompt = "Review the movie Inception.",
    clazz = FilmReview::class.java
)
```

### 3. Annotation-Driven `@Tool` Reflection Loop
Eliminates manual JSON parameter mapping and context-feeding loops:

```kotlin
class DeviceUtilities {
    @Tool("toggle_flashlight", "Turn on/off the device flashlight")
    fun toggleFlashlight(
        @ToolParam("Flashlight state") enabled: Boolean
    ): String {
        return "Flashlight is now ${if (enabled) "ON" else "OFF"}"
    }
}

// Automatically registers tools, dispatches calls via reflection, and streams results
llama.chatWithTools(
    prompt = "It is dark here, please turn on my flashlight.",
    toolHandlers = listOf(DeviceUtilities())
).collect { event ->
    when (event) {
        is GenerationEvent.Token -> print(event.text)
        is GenerationEvent.ToolCall -> println("Executing tool: ${event.name}")
        is GenerationEvent.Done -> println("\nCompleted tool calling sequence.")
    }
}
```

---

## API Subsystems

* **[RAGEngine](RAG.md)**: Features sentence-boundary `SemanticChunker` splits, 1-bit Binary Quantized (BQ) candidate lookup, and on-device C++ JNI Cross-Encoder re-ranking.
* **AudioTranscriber**: Real-time speech-to-text pipeline wrapping Whisper models with hardware-optimized loops.
* **CharacterEngine**: Adjusts model personalities dynamically using logit biases, mood properties, and uncensored modes.

---

## Systrace Profiling

Developers can profile the exact bottlenecks of on-device LLM inference down to the millisecond inside **Android Studio CPU Profiler** using native ATrace tags:

```cpp
// Integrated profile blocks trace execution automatically
TRACE_BEGIN("llama_eval_tokens");
int rc = llama_decode(ctx, batch);
TRACE_END();
```

* **Trace Sessions Provided**: `llama_load_model`, `llama_init_context`, `llama_warmup_decode` (faulting in weight pages), `llama_eval_tokens` (prefill evaluation), and `llama_decode_token` (single token decode loops).

---

## License
This project is licensed under the **MIT License**. It is built on [llama.cpp](https://github.com/ggml-org/llama.cpp) which is also licensed under the MIT License.
