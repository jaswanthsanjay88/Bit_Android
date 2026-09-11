package com.bit.models

import java.util.UUID

/**
 * An Agent Skill following the Agent Skills standard (Claude SKILL.md and JSON formats).
 */
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val icon: String? = null,
    val instructions: String = "",
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class SkillExport(
    val version: Int = 1,
    val format: String = "bit_skill",
    val skill: Skill
)
