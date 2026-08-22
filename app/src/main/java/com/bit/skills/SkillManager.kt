package com.bit.skills

import android.content.Context
import android.util.Log
import com.bit.models.Skill
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("bit_skills_store", Context.MODE_PRIVATE)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val _skills = MutableStateFlow<List<Skill>>(loadSavedSkills())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    init {
        instance = this
    }

    companion object {
        private const val TAG = "SkillManager"
        private const val KEY_SKILLS_JSON = "skills_json"

        @Volatile
        private var instance: SkillManager? = null

        fun getInstance(context: Context): SkillManager {
            return instance ?: synchronized(this) {
                instance ?: SkillManager(context.applicationContext).also { instance = it }
            }
        }

        val DEFAULT_BUILTIN_SKILLS = listOf(
            Skill(
                id = "skill-web-search",
                name = "Web Search & Scraping",
                description = "Searches the live web via DuckDuckGo and scrapes clean markdown text.",
                instructions = "You have access to web search capabilities. Always verify factual claims before answering.",
                icon = "search",
                enabled = true,
                isBuiltIn = true
            ),
            Skill(
                id = "skill-memory-vault",
                name = "AI Memory Vault",
                description = "Autonomously writes important user facts and extracts knowledge graph triples.",
                instructions = "Store persistent user preferences, names, and key facts into the episodic memory vault.",
                icon = "storage",
                enabled = true,
                isBuiltIn = true
            ),
            Skill(
                id = "skill-file-ops",
                name = "File Operations",
                description = "Read and write project files, exports, and markdown documents.",
                instructions = "Execute file inspection and directory listing safely.",
                icon = "terminal",
                enabled = true,
                isBuiltIn = true
            )
        )
    }

    private fun loadSavedSkills(): List<Skill> {
        val rawJson = prefs.getString(KEY_SKILLS_JSON, null) ?: return DEFAULT_BUILTIN_SKILLS

        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<Skill>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                // Exclude removed calculator skill
                if (id == "skill-calculator" || name.contains("calculator", ignoreCase = true)) {
                    continue
                }
                list.add(
                    Skill(
                        id = id,
                        name = name,
                        description = obj.optString("description"),
                        instructions = obj.optString("instructions"),
                        icon = obj.optString("icon", "sparkles"),
                        enabled = obj.optBoolean("enabled", true),
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            if (list.isEmpty()) DEFAULT_BUILTIN_SKILLS else list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved skills", e)
            DEFAULT_BUILTIN_SKILLS
        }
    }

    private fun persistSkills(list: List<Skill>) {
        try {
            val arr = JSONArray()
            for (s in list) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("description", s.description)
                    put("instructions", s.instructions)
                    put("icon", s.icon ?: "sparkles")
                    put("enabled", s.enabled)
                    put("isBuiltIn", s.isBuiltIn)
                    put("createdAt", s.createdAt)
                })
            }
            prefs.edit().putString(KEY_SKILLS_JSON, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting skills", e)
        }
    }

    /**
     * Synchronous in-memory update on caller thread.
     * Disk persistence is fire-and-forget on background IO.
     */
    fun setOrderedSkills(newOrder: List<Skill>) {
        _skills.value = newOrder
        scope.launch {
            persistSkills(newOrder)
        }
    }

    fun addSkill(skill: Skill) {
        val updated = _skills.value + skill
        _skills.value = updated
        scope.launch {
            persistSkills(updated)
        }
    }

    fun updateSkill(skill: Skill) {
        val updated = _skills.value.map {
            if (it.id == skill.id) skill else it
        }
        _skills.value = updated
        scope.launch {
            persistSkills(updated)
        }
    }

    fun removeSkill(skillId: String) {
        val updated = _skills.value.filter { it.id != skillId }
        _skills.value = updated
        scope.launch {
            persistSkills(updated)
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        val updated = _skills.value.map {
            if (it.id == skillId) it.copy(enabled = enabled) else it
        }
        _skills.value = updated
        scope.launch {
            persistSkills(updated)
        }
    }

    fun reorderSkills(fromIndex: Int, toIndex: Int) {
        val list = _skills.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _skills.value = list
            scope.launch {
                persistSkills(list)
            }
        }
    }

    /**
     * Builds lightweight progressive disclosure catalog for tool-capable models.
     * Contains only skill names, triggers, and descriptions. Full instructions
     * are loaded on demand via `manage_skills`.
     */
    fun getSkillCatalogPrompt(): String {
        val active = _skills.value.filter { it.enabled && it.instructions.isNotBlank() }
        if (active.isEmpty()) return ""

        return buildString {
            appendLine("## Available Agent Skills (Progressive Disclosure)")
            appendLine("The following skills are available. They are NOT loaded into your context by default. When a user request clearly requires one of these skills, invoke `manage_skills(skill = \"<skill_name>\")` to load its full instructions:")
            active.forEach { skill ->
                val desc = skill.description.ifBlank { "Specialized skill routine" }
                appendLine("- **${skill.name}**: $desc")
            }
        }
    }

    /**
     * Legacy full instructions prompt (used when tool calling is disabled or for base completion models).
     */
    fun getActiveSkillsPrompt(): String {
        val active = _skills.value.filter { it.enabled && it.instructions.isNotBlank() }
        if (active.isEmpty()) return ""

        return buildString {
            appendLine("## Active Skills & Specializations")
            active.forEach { skill ->
                appendLine("### ${skill.name}")
                if (skill.description.isNotBlank()) {
                    appendLine("Description: ${skill.description}")
                }
                appendLine(skill.instructions)
                appendLine()
            }
        }
    }
}
