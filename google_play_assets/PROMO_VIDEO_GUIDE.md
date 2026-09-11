# Google Play Store Promo Video Guide for BIT

## 1. Google Play Video Requirements Checklist
When submitting a video URL to the Google Play Console:
- **Platform:** Must be hosted on **YouTube** (paste standard watch URL: `https://www.youtube.com/watch?v=VIDEO_ID`).
- **Privacy:** Video must be set to **Public** or **Unlisted** (Private videos are rejected).
- **Monetization / Ads:** **Turn OFF all ads/monetization** on the video. Google Play requires an ad-free experience.
- **Age Restrictions:** Ensure the video is **not age-restricted**.
- **Orientation & Aspect Ratio:** Landscape **16:9** (1920x1080 or 1280x720) is recommended by Google Play for store listings.
- **First 30 Seconds:** Show actual app UI and on-device inference within the first 10-30 seconds (no prolonged splash screens).

---

## 2. Recommended 45-Second Demo Video Script & Storyboard

### Scene 1: Offline Freedom (0:00 - 0:10)
- **Visual:** Close-up of Android status bar showing **Airplane Mode ON** (No Wi-Fi, No Cellular). User opens BIT.
- **Action:** User types: *"Explain quantum superposition in simple terms"*.
- **On-Screen Text Overlay:** *100% On-Device · Zero Cloud Dependency · Private Silicon*
- **Outcome:** Model streams tokens instantaneously at 25+ tokens/sec using local GGUF weights.

### Scene 2: Live Conversational Voice (0:10 - 0:22)
- **Visual:** Tap into Live Voice Mode. Dynamic neural orb pulses smoothly in liquid glass UI.
- **Action:** User speaks: *"Summarize my morning meetings and draft an email response."*
- **On-Screen Text Overlay:** *Sherpa-ONNX Whisper STT · Piper Neural TTS · Full VAD Barge-in*
- **Outcome:** The orb reacts in real-time, speaks back naturally with low-latency neural voice synthesis.

### Scene 3: HuggingFace Model Store & Sampler Tuning (0:22 - 0:34)
- **Visual:** Browse Model Store tab. Show Llama 3.2, Qwen 2.5, Gemma 2, and SmolLM models.
- **Action:** User taps to switch between models, opens Model Config to adjust Temperature, Repetition Penalty, and Context Size up to 32k.
- **On-Screen Text Overlay:** *Direct GGUF Downloads · Storage Import · GBNF Tool Grammar*

### Scene 4: Hybrid RAG & Memory Vault (0:34 - 0:45)
- **Visual:** Query local PDF and document vault. Vector semantic search retrieves relevant citations instantly.
- **On-Screen Text Overlay:** *BIT: Your Autonomous On-Device AI Engine*
- **Closing Title Screen:**
  - BIT Logo
  - *Apache 2.0 Open Source*
  - Available on Google Play & F-Droid

---

## 3. How to Record the Screen Directly from Your Android Device

You can record high-definition video directly using Android Debug Bridge (ADB):

```bash
# 1. Start recording (1080p, 60 seconds limit)
adb shell screenrecord --size 1080x1920 --bit-rate 12000000 /sdcard/bit_demo.mp4

# (Perform the actions on your phone, then press Ctrl+C in terminal when finished)

# 2. Pull the recorded video to your computer
adb pull /sdcard/bit_demo.mp4 ./google_play_assets/bit_demo.mp4
```

---

## 4. YouTube Upload Details (Copy-Paste Ready)

- **Video Title:** `BIT — On-Device Offline AI Engine for Android (GGUF LLM, Whisper Voice, Local RAG)`
- **Video Description:**
```
BIT is an offline, privacy-first mobile AI assistant engineered specifically for Android silicon.

Run production-grade Llama, Qwen, and Gemma models natively on your phone without cloud servers, subscriptions, or telemetry.

Features:
- On-device GGUF LLMs via llama.kt native C++ bindings
- Real-time conversational voice mode (Sherpa Whisper STT + Piper Neural TTS)
- Local Document Search (Hybrid Vector + BM25 RAG)
- HuggingFace Model Store & local storage importer
- GBNF sampler-level tool constraints
- 100% private: All inference runs locally on device hardware

Source Code: https://github.com/jaswanthsanjay88/Bit_Android
License: Apache 2.0
```
- **Category:** Science & Technology / Tools
- **Tags:** `Android AI, On Device LLM, GGUF, Llama 3 Android, Offline AI, Whisper STT, Piper TTS, Private AI, Local RAG`
