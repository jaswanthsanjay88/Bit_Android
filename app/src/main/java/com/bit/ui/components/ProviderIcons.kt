package com.bit.ui.components

import com.bit.R

/**
 * Single source of truth mapping a built-in provider name to its brand icon drawable.
 * Returns 0 for unknown / custom providers (callers fall back to a generic Cloud icon).
 */
fun providerIcon(name: String): Int = when (name.lowercase().trim()) {
    "google", "google gemini", "gemini" -> R.drawable.provider_google
    "openai" -> R.drawable.provider_openai
    "anthropic", "anthropic claude", "claude" -> R.drawable.provider_anthropic
    "deepseek" -> R.drawable.provider_deepseek
    "qwen" -> R.drawable.provider_qwen
    "ollama" -> R.drawable.provider_ollama
    "open router", "openrouter" -> R.drawable.provider_openrouter
    "huggingface", "hugging face", "hf" -> R.drawable.provider_huggingface
    "nvidia", "nvidia nim", "nim" -> R.drawable.provider_nvidia
    else -> 0
}
