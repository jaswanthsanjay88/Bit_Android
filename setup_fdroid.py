import os
import shutil
from PIL import Image, ImageDraw, ImageFont

FASTLANE_DIR = os.path.join("fastlane", "metadata", "android", "en-US")
IMAGES_DIR = os.path.join(FASTLANE_DIR, "images")
PHONE_SHOTS_DIR = os.path.join(IMAGES_DIR, "phoneScreenshots")
CHANGELOG_DIR = os.path.join(FASTLANE_DIR, "changelogs")
FDROID_DIR = "fdroid"

# Create directories
os.makedirs(PHONE_SHOTS_DIR, exist_ok=True)
os.makedirs(CHANGELOG_DIR, exist_ok=True)
os.makedirs(FDROID_DIR, exist_ok=True)

# 1. Text metadata
with open(os.path.join(FASTLANE_DIR, "title.txt"), "w", encoding="utf-8") as f:
    f.write("BIT - Offline On-Device AI Engine")

with open(os.path.join(FASTLANE_DIR, "short_description.txt"), "w", encoding="utf-8") as f:
    f.write("Offline Android AI stack with GGUF LLMs, speech recognition, voice synthesis & GBNF tools.")

with open(os.path.join(FASTLANE_DIR, "full_description.txt"), "w", encoding="utf-8") as f:
    f.write("""BIT is an offline, privacy-first mobile AI engine engineered specifically for Android silicon.

Features:
- On-Device GGUF LLMs: Run quantized Llama, Qwen, Gemma, and Mistral models locally via llama.kt native C++ bindings.
- Offline Speech Pipeline: Sherpa-ONNX Whisper STT for 16kHz audio recognition and Piper VITS neural TTS voice synthesis with VAD barge-in.
- GBNF Sampler Tool Router: Token-level grammar constraints forcing valid structured JSON tool calls.
- Memory Vault & Hybrid RAG: Cross-session episodic preference memory with decay curves and local document search (Vector + BM25 keyword matching).
- HuggingFace Model Store: Browse, download, and switch quantized model weights directly on device storage.
- 100% Privacy & Zero Network Dependency: All inference, speech processing, and document indexing run entirely offline on local silicon.
""")

with open(os.path.join(CHANGELOG_DIR, "62.txt"), "w", encoding="utf-8") as f:
    f.write("""v1.9.3 Release:
- Liquid Glass monochrome UI with Samsung Galaxy S24/S25 Ultra device presentation.
- Optimized GGUF model selector and HuggingFace store parser.
- Sherpa-ONNX Whisper STT model load fixes and audio stability enhancements.
- Per-model sampling controls for temperature, context length, and repetition penalty.
""")

# 2. Copy phone screenshots
src_shots = [
    ("marketing_assets/google_play_1080x1920/01_chat_interface.png", "1_chat.png"),
    ("marketing_assets/google_play_1080x1920/02_live_voice.png", "2_voice.png"),
    ("marketing_assets/google_play_1080x1920/03_model_store.png", "3_store.png"),
    ("marketing_assets/google_play_1080x1920/04_model_editor.png", "4_editor.png")
]

for src, dst_name in src_shots:
    if os.path.exists(src):
        shutil.copy(src, os.path.join(PHONE_SHOTS_DIR, dst_name))
        print(f"Copied screenshot: {dst_name}")

# 3. Generate 512x512 Icon
icon_img = Image.new("RGBA", (512, 512), (10, 10, 10, 255))
draw_icon = ImageDraw.Draw(icon_img)
# Draw subtle border
draw_icon.rounded_rectangle([16, 16, 496, 496], radius=96, fill=(18, 18, 18, 255), outline=(60, 60, 60, 255), width=6)
# Try loading SVG or raster logo, or draw crisp "BIT" logo mark
try:
    font_bold = ImageFont.truetype("arialbd.ttf", 180)
    font_sub = ImageFont.truetype("arial.ttf", 48)
except Exception:
    font_bold = ImageFont.load_default()
    font_sub = ImageFont.load_default()

draw_icon.text((256, 220), "BIT", font=font_bold, fill=(255, 255, 255, 255), anchor="mm")
draw_icon.text((256, 350), "OFFLINE AI", font=font_sub, fill=(140, 140, 140, 255), anchor="mm")

icon_path = os.path.join(IMAGES_DIR, "icon.png")
icon_img.save(icon_path)
print(f"Generated Icon: {icon_path}")

# 4. Generate 1024x500 Feature Graphic
fg_img = Image.new("RGBA", (1024, 500), (8, 8, 8, 255))
draw_fg = ImageDraw.Draw(fg_img)

# Background subtle grid / glow
draw_fg.rectangle([0, 0, 1024, 500], fill=(10, 10, 12, 255))
draw_fg.rounded_rectangle([40, 40, 984, 460], radius=24, fill=(16, 16, 18, 255), outline=(45, 45, 50, 255), width=2)

try:
    font_fg_title = ImageFont.truetype("arialbd.ttf", 64)
    font_fg_sub = ImageFont.truetype("arial.ttf", 26)
    font_fg_pill = ImageFont.truetype("arialbd.ttf", 20)
except Exception:
    font_fg_title = ImageFont.load_default()
    font_fg_sub = ImageFont.load_default()
    font_fg_pill = ImageFont.load_default()

draw_fg.text((80, 140), "BIT — Offline AI Engine", font=font_fg_title, fill=(255, 255, 255, 255))
draw_fg.text((80, 220), "On-Device GGUF LLM · Sherpa Whisper STT · Piper Voice · GBNF Tools", font=font_fg_sub, fill=(160, 160, 160, 255))

# Draw feature pills
pills = ["100% OFFLINE", "APACHE 2.0", "SILICON OPTIMIZED", "NO TELEMETRY"]
curr_x = 80
for p in pills:
    bbox = font_fg_pill.getbbox(p)
    pw = (bbox[2] - bbox[0]) + 32
    draw_fg.rounded_rectangle([curr_x, 320, curr_x + pw, 360], radius=20, fill=(255, 255, 255, 255))
    draw_fg.text((curr_x + pw/2, 340), p, font=font_fg_pill, fill=(0, 0, 0, 255), anchor="mm")
    curr_x += pw + 16

fg_path = os.path.join(IMAGES_DIR, "featureGraphic.png")
fg_img.save(fg_path)
print(f"Generated Feature Graphic: {fg_path}")

# 5. Create F-Droid Metadata Recipe File (com.bit.yml)
fdroid_yml = """Categories:
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
  - versionName: 1.9.3
    versionCode: 62
    commit: v1.9.3
    subdir: app
    gradle:
      - yes
    output: build/outputs/apk/release/app-universal-release-unsigned.apk
    ndk: r28c

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: 1.9.3
CurrentVersionCode: 62
"""

with open(os.path.join(FDROID_DIR, "com.bit.yml"), "w", encoding="utf-8") as f:
    f.write(fdroid_yml)
print("Generated F-Droid Metadata Recipe: fdroid/com.bit.yml")

print("F-Droid preparation complete!")
