package com.bit.util

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object SearchResultFormatter {

    fun isRawSearchResult(text: String): Boolean = try {
        val type = Json.parseToJsonElement(text).jsonObject["type"]?.let { (it as? JsonPrimitive)?.content }
        type == "web_search"
    } catch (_: Exception) { false }

    fun format(text: String, context: Context): String {
        if (!isRawSearchResult(text)) return text
        return try {
            val json = Json.parseToJsonElement(text).jsonObject
            val error = json["error"]?.let { (it as? JsonPrimitive)?.content }
            if (error != null) return formatError(json, error)
            when (json["type"]?.let { (it as? JsonPrimitive)?.content }) {
                "web_search" -> formatWebSearch(json)
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }

    fun getFirstLine(text: String, context: Context): String {
        val formatted = format(text, context)
        return formatted.lines().first().take(100)
    }

    private fun formatError(json: JsonObject, error: String): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        return when (error) {
            "no_query" -> "Error: No search query was provided."
            "no_results" -> "No results found for search query: \"$query\""
            "no_response" -> "Error: No response from search provider."
            "no_api_key" -> "Error: Search API key is not configured."
            "search_error" -> {
                val msg = json["message"]?.let { (it as? JsonPrimitive)?.content } ?: "Unknown error"
                "Search error: $msg"
            }
            else -> "Search error: $error"
        }
    }

    private fun formatWebSearch(json: JsonObject): String {
        val query = json["query"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val results = json["results"]?.jsonArray ?: return "No results found."
        if (results.isEmpty()) return "No results found."

        val untitled = "Untitled"
        val body = results.take(10).mapIndexed { i, element ->
            val obj = element.jsonObject
            val title = (obj["title"] as? JsonPrimitive)?.content ?: untitled
            val url = (obj["url"] as? JsonPrimitive)?.content ?: ""
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: (obj["snippet"] as? JsonPrimitive)?.content ?: ""
            "${i + 1}. $title\n   $url\n   $desc"
        }.joinToString("\n\n")

        val total = results.size
        val prefix = if (query.isNotBlank())
            "Found $total search results for query: \"$query\""
        else
            "Found $total search results"
        return "$prefix\n\n$body"
    }
}
