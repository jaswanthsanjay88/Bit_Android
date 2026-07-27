# BIT — F-Droid Publishing & Submission Guide

This directory contains everything required to submit **BIT** to **F-Droid** (the official free and open-source Android app repository).

---

## 1. Project F-Droid Readiness Checklist

| Requirement | Status | Location / Details |
| :--- | :---: | :--- |
| **Open Source License** | ✅ | [Apache License 2.0 / MIT](file:///e:/BIT/LICENSE) |
| **No Proprietary Trackers** | ✅ | 100% Offline, no telemetry or analytics |
| **Fastlane App Metadata** | ✅ | [`fastlane/metadata/android/en-US/`](file:///e:/BIT/fastlane/metadata/android/en-US) |
| **512x512 PNG Icon** | ✅ | [`fastlane/metadata/android/en-US/images/icon.png`](file:///e:/BIT/fastlane/metadata/android/en-US/images/icon.png) |
| **1024x500 Feature Graphic** | ✅ | [`fastlane/metadata/android/en-US/images/featureGraphic.png`](file:///e:/BIT/fastlane/metadata/android/en-US/images/featureGraphic.png) |
| **High-Res Screenshots** | ✅ | [`fastlane/metadata/android/en-US/images/phoneScreenshots/`](file:///e:/BIT/fastlane/metadata/android/en-US/images/phoneScreenshots) |
| **F-Droid Build Recipe** | ✅ | [`fdroid/com.bit.yml`](file:///e:/BIT/fdroid/com.bit.yml) |
| **Reproducible Build Target** | ✅ | `build/outputs/apk/release/app-universal-release-unsigned.apk` |

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

## 3. F-Droid Metadata Recipe (`metadata/com.bit.yml`)

```yaml
Categories:
  - AI Chat
  - System
License: MIT
AuthorName: Jaswanth Sanjay
AuthorEmail: jaswanthsanjay88@gmail.com
SourceCode: https://github.com/jaswanthsanjay88/Bit_Android
IssueTracker: https://github.com/jaswanthsanjay88/Bit_Android/issues

Name: Bit
AutoName: BIT

RepoType: git
Repo: https://github.com/jaswanthsanjay88/Bit_Android
Binaries: 
  https://github.com/jaswanthsanjay88/Bit_Android/releases/download/v%v/app-universal-release.apk

Builds:
  - versionName: 1.9.4
    versionCode: 63
    commit: 4a7de2a6cda72f439455ba8b396ff194608e2286
    subdir: app
    gradle:
      - yes
    output: build/outputs/apk/release/app-universal-release-unsigned.apk
    ndk: r28c

AllowedAPKSigningKeys: 54be788e5934c826a1bd10b043072fc04e5e4f206067ce5fc183cb8e0c5e4b3b

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.9.4
CurrentVersionCode: 63
```
