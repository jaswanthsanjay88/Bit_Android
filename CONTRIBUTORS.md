# Contributors & Related Projects

BIT is built across multiple repositories. Here's how they fit together.

---

## Project Ecosystem

### [BIT](https://github.com/jaswanthsanjay88/BIT_Android)
The main Android app. UI, chat engine, RAG pipeline, plugin system, AI memory, backup/restore — everything the user interacts with.

---

## How They Connect

```
BIT (app)
    |
    +-- Ai-Systems-New (native AI libraries)
    |       |
    |       +-- llama.cpp-android (GGUF inference JNI bridge)
    |       +-- Stable Diffusion engine
    |       +-- TTS engine (ONNX Runtime)
    |       +-- Embedding engine
    |
    +-- ums (Unified Memory System, in-repo module)
    +-- neuron-packet (encrypted RAG format, in-repo module)
    +-- system_encryptor (native crypto, in-repo module)
```

---

## Contributing

Contributions are welcome across all three repos. If you're working on:

- **UI, chat, plugins, RAG, memory** — contribute to [BIT](https://github.com/jaswanthsanjay88/BIT_Android)
- **Inference performance, model loading, native crashes** — contribute to [Ai-Systems-New](https://github.com/jaswanthsanjay88/Ai-Systems-New) or [llama.cpp-android](https://github.com/jaswanthsanjay88/llama.cpp-android)

See the main [README](README.md) for contribution guidelines.

---

## Maintainer

**[Jaswanth Sanjay](https://github.com/jaswanthsanjay88)** — creator and primary maintainer of all three repositories.

---

## Contributors

<!-- Add contributors here as the project grows -->

Want to see your name here? Check the open issues on any of the repos above and submit a PR.
