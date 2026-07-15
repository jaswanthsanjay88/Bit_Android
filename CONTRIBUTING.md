# Contributing to BIT

Thank you for your interest in contributing to BIT. This document provides guidelines and information for contributors.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](https://www.contributor-covenant.org/version/2/1/code_of_conduct/). By participating, you agree to uphold this standard.

---

## How to Contribute

### Reporting Issues

Before opening a new issue, search the [existing issues](https://github.com/jaswanthsanjay88/Bit_Android/issues) to avoid duplicates.

When reporting a bug, include:

- **Device model** (e.g., Pixel 8 Pro, Samsung Galaxy S24)
- **Android version** (e.g., Android 14, API 34)
- **BIT version** (visible in Settings)
- **Steps to reproduce** the issue
- **Expected behavior** vs. **actual behavior**
- **Logs** if applicable (use `adb logcat` filtered to `com.bit`)

For feature requests, describe the use case and why existing functionality does not address it.

### Submitting Pull Requests

1. **Fork** the repository and create a branch from `master`.
2. **Name your branch** descriptively: `fix/update-checker-skip-version`, `feature/markdown-changelog`, `docs/contributing-guide`.
3. **Write clear commit messages.** Each commit should represent a single logical change.
4. **Test your changes.** Run `./gradlew compileDebugKotlin` at minimum. If your change touches UI, test on a physical device or emulator.
5. **Open a pull request** against `master` with a description of what changed and why.

---

## Development Setup

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Ladybug (2024.2) or later |
| Android SDK | API 36 (compile), API 29 (minimum) |
| Android NDK | Required for native llama.cpp compilation |
| CMake | 3.22 or later |
| JDK | 17 |

### Building

```bash
# Clone with submodules
git clone --recursive https://github.com/jaswanthsanjay88/Bit_Android.git
cd Bit_Android

# Debug build
./gradlew assembleDebug

# Compile check only (faster)
./gradlew compileDebugKotlin
```

### Project Structure

```
BIT/
├── app/                    Main application module
│   └── src/main/java/com/bit/
│       ├── activity/       Activities and entry points
│       ├── composables/    Reusable Compose UI components
│       ├── data/           Data models and default configurations
│       ├── navigation/     Navigation graph and screen definitions
│       ├── plugins/        Tool calling, GBNF grammar, plugin system
│       ├── update/         In-app update checker and downloader
│       ├── viewmodel/      ViewModels for state management
│       └── ui/theme/       Design system, colors, typography
├── llama-kt/               Native inference SDK (llama.cpp JNI)
├── memory-vault/           Episodic memory storage engine
├── neuron-packet/          Encrypted RAG document format
├── system_encryptor/       AES-256-GCM cryptographic utilities
├── file_ops/               File management and backup
├── ums/                    User management system
└── libs/                   Pre-built native AI libraries (.aar)
```

---

## Contribution Areas

| Area | Module | Description |
|---|---|---|
| UI and UX | `app` | Jetpack Compose screens, themes, animations, accessibility |
| Chat Engine | `app` | Message handling, context management, streaming |
| Tool Calling | `app/plugins` | Tool definitions, GBNF grammar generation, routing logic |
| RAG Pipeline | `app`, `neuron-packet` | Document parsing, chunking, vector indexing, hybrid retrieval |
| Native Inference | `llama-kt` | JNI bindings, model loading, sampler configuration |
| Memory System | `memory-vault` | Episodic extraction, decay curves, retrieval |
| Security | `system_encryptor` | Encryption, keystore integration, secure storage |
| Documentation | Root | README, guides, inline documentation |

---

## Code Style

- **Language**: Kotlin (application), C++ (native libraries)
- **UI Framework**: Jetpack Compose with Material 3
- **Formatting**: Follow the project's existing code style. Use 4-space indentation for Kotlin.
- **Documentation**: Add KDoc comments to public APIs. Include inline comments for non-obvious logic.
- **Naming**: Use descriptive names. Avoid abbreviations except for well-known terms (e.g., LLM, RAG, GGUF).

---

## Release Process

Releases follow semantic versioning (`MAJOR.MINOR.PATCH`):

- **PATCH**: Bug fixes, documentation updates, minor improvements
- **MINOR**: New features, non-breaking API changes
- **MAJOR**: Breaking changes, significant architectural updates

Each release is tagged on GitHub with pre-built APK binaries for ARM64, x86_64, and universal architectures.

---

## Questions

If you have questions about the project or need guidance before contributing, open a [discussion](https://github.com/jaswanthsanjay88/Bit_Android/discussions) or reach out through an issue.
