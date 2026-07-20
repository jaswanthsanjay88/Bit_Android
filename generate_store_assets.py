import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_marketing_screenshot(
    output_path,
    width,
    height,
    tag_text,
    title_text,
    sub_text,
    screenshot_path
):
    # Base dark canvas
    canvas = Image.new("RGBA", (width, height), (10, 10, 10, 255))
    draw = ImageDraw.Draw(canvas)

    # Subtly draw subtle grid or gradient
    # Load fonts (fallback to default if system fonts fail)
    try:
        font_tag = ImageFont.truetype("arial.ttf", int(height * 0.016))
        font_title = ImageFont.truetype("arial.ttf", int(height * 0.034))
        font_sub = ImageFont.truetype("arial.ttf", int(height * 0.018))
    except Exception:
        font_tag = ImageFont.load_default()
        font_title = ImageFont.load_default()
        font_sub = ImageFont.load_default()

    # Text positioning
    padding_x = int(width * 0.08)
    curr_y = int(height * 0.07)

    # Draw Tag
    draw.text((padding_x, curr_y), tag_text.upper(), font=font_tag, fill=(140, 140, 140, 255))
    curr_y += int(height * 0.03)

    # Draw Title (support newlines)
    for line in title_text.split("\n"):
        draw.text((padding_x, curr_y), line, font=font_title, fill=(255, 255, 255, 255))
        curr_y += int(height * 0.042)

    curr_y += int(height * 0.01)

    # Draw Subtitle (wrap line if long)
    draw.text((padding_x, curr_y), sub_text, font=font_sub, fill=(160, 160, 160, 255))

    # Place Phone Screenshot Mockup
    if os.path.exists(screenshot_path):
        app_shot = Image.open(screenshot_path).convert("RGBA")
        
        # Grayscale filter
        app_shot = app_shot.convert("L").convert("RGBA")

        # Phone frame dimensions
        phone_w = int(width * 0.72)
        phone_h = int(height * 0.68)
        phone_x = int((width - phone_w) / 2)
        phone_y = int(height * 0.28)

        # Scale app shot to fit inside phone frame
        app_shot_resized = app_shot.resize((phone_w - 20, phone_h - 20), Image.Resampling.LANCZOS)

        # Phone bezel frame (Samsung Galaxy S24 / S25 Ultra Titanium Frame)
        bezel = Image.new("RGBA", (phone_w, phone_h), (10, 10, 12, 255))
        bezel_draw = ImageDraw.Draw(bezel)
        
        # Draw Samsung Armor Titanium Edge
        bezel_draw.rectangle([0, 0, phone_w, phone_h], outline=(50, 52, 58, 255), width=6)
        
        # Rounded corners for phone screen
        bezel.paste(app_shot_resized, (10, 10))

        # Draw Samsung Galaxy S24/S25 Ultra Top Speaker Slit
        speaker_w = int(phone_w * 0.14)
        speaker_h = int(phone_h * 0.004)
        speaker_x = int((phone_w - speaker_w) / 2)
        bezel_draw.rectangle([speaker_x, 11, speaker_x + speaker_w, 11 + speaker_h], fill=(58, 59, 64, 255))

        # Draw Samsung Galaxy S24/S25 Ultra Infinity-O Centered Camera Punchhole
        camera_r = int(phone_w * 0.02)
        camera_x = int(phone_w / 2)
        camera_y = int(phone_h * 0.028)
        bezel_draw.ellipse([camera_x - camera_r, camera_y - camera_r, camera_x + camera_r, camera_y + camera_r], fill=(7, 7, 8, 255), outline=(40, 41, 46, 255), width=2)
        
        # Camera Lens Reflection
        lens_r = int(camera_r * 0.4)
        bezel_draw.ellipse([camera_x - lens_r, camera_y - lens_r, camera_x + lens_r, camera_y + lens_r], fill=(88, 114, 184, 255))

        # Paste phone mockup onto canvas
        canvas.paste(bezel, (phone_x, phone_y))

    # Save PNG
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    canvas.save(output_path, "PNG")
    print(f"Generated: {output_path}")

# Slide Definitions
SLIDES = [
    {
        "filename": "01_chat_interface.png",
        "tag": "ON-DEVICE GGUF LLM",
        "title": "Offline Intelligence.\nZero Cloud Dependency.",
        "sub": "Run Llama, Qwen, and Gemma models fully locally on Android silicon.",
        "src": "docs/img/bit_chat_interface.jpg"
    },
    {
        "filename": "02_live_voice.png",
        "tag": "HANDS-FREE CONVERSATIONAL UI",
        "title": "Live Voice Mode.\nDynamic Pulsing Orb.",
        "sub": "Sherpa-ONNX Whisper STT paired with Piper TTS voice synthesis and VAD.",
        "src": "docs/img/bit_live_voice_mode.jpg"
    },
    {
        "filename": "03_model_store.png",
        "tag": "GGUF MODEL MANAGEMENT",
        "title": "HuggingFace Store &\nLocal SAF Importer.",
        "sub": "Search, download, and switch quantized GGUF weights directly on storage.",
        "src": "docs/img/bit_model_store.jpg"
    },
    {
        "filename": "04_model_editor.png",
        "tag": "SAMPLER-LEVEL CONTROLS",
        "title": "Per-Model Config &\nGBNF Tool Router.",
        "sub": "Tune temperature, context size up to 32k, repetition penalty, and GBNF tools.",
        "src": "docs/img/bit_model_editor.jpg"
    }
]

# Generate Google Play Store (1080x1920)
for s in SLIDES:
    out = os.path.join("marketing_assets", "google_play_1080x1920", s["filename"])
    create_marketing_screenshot(out, 1080, 1920, s["tag"], s["title"], s["sub"], s["src"])

# Generate Apple App Store (1290x2796)
for s in SLIDES:
    out = os.path.join("marketing_assets", "app_store_1290x2796", s["filename"])
    create_marketing_screenshot(out, 1290, 2796, s["tag"], s["title"], s["sub"], s["src"])

print("All marketing screenshot assets successfully generated!")
