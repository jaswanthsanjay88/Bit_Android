package com.bit.agent.harness.engine

import com.bit.agent.harness.model.VerifiedClaim
import kotlinx.serialization.json.Json

/**
 * Extracts structured verification claims from a reviewer subagent's free-text report.
 *
 * The subagent is prompted (not forced) to end its report with a JSON block:
 *     {"claims":[{"claim":"...","status":"VERIFIED|CONTRADICTED|UNCERTAIN","sources":["..."],"notes":"..."}]}
 *
 * Parsing is best-effort: any failure returns an empty list and the caller keeps
 * rendering the raw markdown output. Never throws.
 */
object ClaimExtractor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extract(reportText: String): List<VerifiedClaim> {
        if (reportText.isBlank()) return emptyList()
        return runCatching {
            val candidate = findJsonCandidate(reportText) ?: return emptyList()
            val parsed = json.decodeFromString<ClaimsEnvelope>(candidate)
            parsed.claims
                .filter { it.claim.isNotBlank() }
                .map { it.normalizeStatus() }
        }.getOrDefault(emptyList())
    }

    @kotlinx.serialization.Serializable
    private data class ClaimsEnvelope(val claims: List<RawClaim> = emptyList())

    @kotlinx.serialization.Serializable
    private data class RawClaim(
        val claim: String = "",
        val status: String = "",
        val sources: List<String> = emptyList(),
        val notes: String = ""
    )

    private fun RawClaim.normalizeStatus(): VerifiedClaim {
        val canonical = when {
            status.uppercase().contains("VERIFIED") && status.uppercase().contains("UN") -> "UNCERTAIN"
            status.uppercase().contains("CONTRADICT") || status.uppercase().contains("FALSE") -> "CONTRADICTED"
            status.uppercase().contains("VERIF") -> "VERIFIED"
            status.uppercase().contains("UNCERTAIN") || status.uppercase().contains("UNCONFIRM") -> "UNCERTAIN"
            else -> "UNCERTAIN"
        }
        return VerifiedClaim(claim = claim.trim(), status = canonical, sources = sources, notes = notes)
    }

    /** Finds a JSON object containing a "claims" array: fenced block first, then bare scan. */
    private fun findJsonCandidate(text: String): String? {
        // 1. ```json fenced blocks, last one wins (report ends with the block per prompt)
        val fenceRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\"claims\"[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
        fenceRegex.findAll(text).lastOrNull()?.let { return it.groupValues[1] }

        // 2. Bare object containing "claims"
        val keyIdx = text.lastIndexOf("\"claims\"")
        if (keyIdx >= 0) {
            val start = text.lastIndexOf('{', keyIdx)
            if (start >= 0) {
                var depth = 0
                var inString = false
                var escape = false
                for (i in start until text.length) {
                    val c = text[i]
                    if (escape) { escape = false; continue }
                    when {
                        c == '\\' && inString -> escape = true
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> depth++
                        !inString && c == '}' -> {
                            depth--
                            if (depth == 0) return text.substring(start, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }
}
