# BIT — F-Droid Publishing & Submission Guide

This directory contains everything required to submit **BIT** to **F-Droid** (the official free and open-source Android app repository).

---

## 1. Project F-Droid Readiness Checklist

| Requirement | Status | Location / Details |
| :--- | :---: | :--- |
| **Open Source License** | ✅ | [Apache License 2.0](file:///e:/BIT/LICENSE) |
| **No Proprietary Trackers** | ✅ | 100% Offline, no telemetry or analytics |
| **Fastlane App Metadata** | ✅ | [`fastlane/metadata/android/en-US/`](file:///e:/BIT/fastlane/metadata/android/en-US) |
| **512x512 PNG Icon** | ✅ | [`fastlane/metadata/android/en-US/images/icon.png`](file:///e:/BIT/fastlane/metadata/android/en-US/images/icon.png) |
| **1024x500 Feature Graphic** | ✅ | [`fastlane/metadata/android/en-US/images/featureGraphic.png`](file:///e:/BIT/fastlane/metadata/android/en-US/images/featureGraphic.png) |
| **High-Res Screenshots** | ✅ | [`fastlane/metadata/android/en-US/images/phoneScreenshots/`](file:///e:/BIT/fastlane/metadata/android/en-US/images/phoneScreenshots) |
| **F-Droid Build Recipe** | ✅ | [`fdroid/com.bit.yml`](file:///e:/BIT/fdroid/com.bit.yml) |
| **Reproducible Build Target** | ✅ | Gradle `assembleUniversalRelease` |

---

## 2. F-Droid Metadata Structure (`fastlane/metadata/android/en-US/`)

F-Droid automatically fetches store graphics, changelogs, and copy directly from your repository's Fastlane layout:

```
fastlane/metadata/android/en-US/
├── title.txt
├── short_description.txt
├── full_description.txt
├── changelogs/
│   └── 63.txt
└── images/
    ├── icon.png                      # 512x512 PNG
    ├── featureGraphic.png            # 1024x500 PNG
    └── phoneScreenshots/
        ├── 1_chat.png
        ├── 2_voice.png
        ├── 3_store.png
        └── 4_editor.png
```

---

## 3. How to Submit BIT to F-Droid via GitLab (Official Canonical Repo)

> [!IMPORTANT]
> The GitHub repo `f-droid/fdroiddata` is a **read-only mirror**. F-Droid accepts all submissions and Merge Requests on **GitLab**: [https://gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).

---

### Method A: Submit a Merge Request (MR) on GitLab (Fastest — 1 Click GitHub Login)

1. **Sign in to GitLab**:
   - Go to [https://gitlab.com/users/sign_in](https://gitlab.com/users/sign_in)
   - Click **"Sign in with GitHub"** (Log in directly using your GitHub credentials without creating a new password).

2. **Fork `fdroiddata` on GitLab**:
   - Open F-Droid's canonical repository: [https://gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata)
   - Click **Fork** (top right) to create your fork (`https://gitlab.com/jaswanthsanjay88/fdroiddata`).

3. **Add `metadata/com.bit.yml`**:
   - In your GitLab fork, navigate into the `metadata/` directory.
   - Click **+** → **New file**.
   - File name: `metadata/com.bit.yml`
   - Paste the contents of [`fdroid/com.bit.yml`](file:///e:/BIT/fdroid/com.bit.yml):

```yaml
Categories:
  - Science & Education
  - System
  - Development
License: Apache-2.0
AuthorName: Jaswanthsanjay
AuthorEmail: jaswanthsanjay88@gmail.com
SourceCode: https://github.com/jaswanthsanjay88/Bit_Android
IssueTracker: https://github.com/jaswanthsanjay88/Bit_Android/issues
Changelog: https://github.com/jaswanthsanjay88/Bit_Android/releases
Donate: https://buymeacoffee.com/jaswanthsanjay

AutoName: BIT
Summary: Offline On-Device AI Engine for Android
Description: |-
  BIT is a privacy-first mobile AI stack running entirely on-device without remote servers.

  Key Capabilities:
  - On-Device GGUF LLMs: Execute quantized Llama, Qwen, Gemma, and Mistral models via llama.kt native C++ JNI bindings.
  - Offline Speech Pipeline: Sherpa-ONNX Whisper STT 16kHz speech recognition paired with Piper VITS neural voice synthesis and VAD barge-in.
  - GBNF Tool Router: Token-level grammar sampling forcing valid JSON tool invocation.
  - Memory Vault & Hybrid RAG: Cross-session episodic memory decay and local document indexing (Vector + BM25).
  - HuggingFace Model Importer: Download and manage GGUF models directly on device storage.

RepoType: git
Repo: https://github.com/jaswanthsanjay88/Bit_Android.git

Builds:
  - versionName: 1.9.4
    versionCode: 63
    commit: v1.9.4
    subdir: app
    gradle:
      - yes
    output: build/outputs/apk/release/app-universal-release-unsigned.apk
    ndk: r28c

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.9.4
CurrentVersionCode: 63
```

4. **Open Merge Request (MR)**:
   - Click **Commit changes**.
   - Click **Create Merge Request**.
   - Title: `New App: com.bit (BIT - Offline On-Device AI Engine)`
   - F-Droid's automated build runner (`fdroid build`) will run a test build against tag `v1.9.3` and merge your recipe!

---

### Method B: Open an RFP (Request For Packaging) Issue on GitLab

If you prefer F-Droid maintainers to submit the recipe for you:
1. Open a new issue at: [https://gitlab.com/fdroid/rfp/-/issues/new](https://gitlab.com/fdroid/rfp/-/issues/new)
2. Title: `RFP: BIT (com.bit)`
3. Under **Type**, select template **Request For Packaging (RFP)**.
4. Paste your repository link (`https://github.com/jaswanthsanjay88/Bit_Android`) and attach `fdroid/com.bit.yml`.
5. Submit the issue!

---

## 4. F-Droid Badge Integration

Add the official F-Droid badge to your [`README.md`](file:///e:/BIT/README.md) and [`site/index.html`](file:///e:/BIT/site/index.html):

```html
<a href="https://f-droid.org/packages/com.bit">
  <img src="https://fdroid.gitlab.io/fdroid-website/badge/get-it-on.png" alt="Get it on F-Droid" height="60">
</a>
```
