# Contributing to llama.kt

Thank you for your interest in contributing to llama.kt. This document provides guidelines and instructions for contributing.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Coding Standards](#coding-standards)
- [Pull Request Process](#pull-request-process)
- [Reporting Issues](#reporting-issues)

---

## Code of Conduct

This project follows a standard code of conduct. Be respectful, constructive, and collaborative. Harassment, discrimination, and abusive behavior are not tolerated.

---

## Getting Started

1. Fork the repository.
2. Clone your fork locally.
3. Create a feature branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```
4. Make your changes.
5. Test your changes (see [Testing](#testing) below).
6. Commit with a clear message and push to your fork.
7. Open a pull request against `main`.

---

## Development Setup

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Ladybug (2024.2+) |
| Android NDK | 27.3+ |
| CMake | 3.22.1+ |
| JDK | 17+ |
| Kotlin | 1.9+ |

### Building

```bash
git clone https://github.com/user/llama.kt.git
cd llama.kt
./gradlew assembleDebug
```

### Testing

```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests (requires device)
```

---

## Coding Standards

### Kotlin

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Use data classes for value types.
- Prefer `sealed class` for closed type hierarchies.
- Use coroutines and Flow for asynchronous operations.
- All public API methods must have KDoc documentation.
- Use `internal` visibility for implementation details.

### C++ (Native Layer)

- Follow the existing llama.cpp coding style.
- Use C++17 features.
- All JNI functions must validate inputs and handle exceptions.
- Use `LOGI`, `LOGW`, `LOGE` macros for logging (never `printf` or `std::cout`).
- Free all JNI local references to prevent reference table overflow.

### Naming Conventions

| Type | Convention | Example |
|---|---|---|
| Kotlin classes | PascalCase | `GGMLEngine` |
| Kotlin functions | camelCase | `generateFlow()` |
| JNI methods | `native` prefix | `nativeLoadModel()` |
| C++ globals | `g_` prefix | `g_state` |
| Constants | UPPER_SNAKE_CASE | `TOKEN_BATCH_THRESHOLD` |

---

## Pull Request Process

1. **One concern per PR.** Keep pull requests focused on a single feature, fix, or improvement.

2. **Write tests.** If adding a new feature, include unit tests. If fixing a bug, include a regression test.

3. **Update documentation.** If your change affects the public API, update the README and KDoc comments.

4. **Describe your changes.** Write a clear PR description explaining:
   - What the change does
   - Why the change is needed
   - How it was tested

5. **Keep commits clean.** Use descriptive commit messages. Squash WIP commits before submitting.

6. **PR Title Format:**
   ```
   feat: Add batch embedding support
   fix: Resolve memory leak in context cleanup
   docs: Update tool calling examples
   perf: Optimize token batching threshold
   refactor: Extract sampling config into separate class
   ```

---

## Reporting Issues

When reporting a bug, include:

- **Device information:** Model, SoC, RAM, Android version
- **Model file:** Name, quantization level, parameter count
- **Steps to reproduce:** Minimal code to trigger the issue
- **Expected behavior:** What should happen
- **Actual behavior:** What happens instead
- **Logs:** Logcat output filtered by `ToolNeuron-JNI`

### Issue Labels

| Label | Description |
|---|---|
| `bug` | Something is broken |
| `enhancement` | New feature request |
| `performance` | Speed or memory improvement |
| `documentation` | Documentation updates |
| `good first issue` | Suitable for new contributors |
| `help wanted` | Extra attention needed |

---

## Areas of Contribution

Contributions are welcome in the following areas:

- **New model architectures** — Adding support for new GGUF model types
- **Performance** — Profiling and optimizing inference on specific SoCs
- **Testing** — Unit tests, integration tests, benchmark suites
- **Documentation** — Usage examples, tutorials, API documentation
- **Platform support** — Extending beyond arm64-v8a (x86_64 emulator, etc.)
- **Kotlin Multiplatform** — Desktop/iOS support via KMP

---

## Questions

If you have questions about contributing, open a discussion on the repository or reach out via issues.
