package com.bit.models.data

enum class ModelCategory {
    GENERAL,
    MEDICAL,
    RESEARCH,
    CODING,
    UNCENSORED,
    BUSINESS,
    CYBERSECURITY;

    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
