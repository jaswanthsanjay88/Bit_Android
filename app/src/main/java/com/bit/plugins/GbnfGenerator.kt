package com.bit.plugins

import org.json.JSONObject

object GbnfGenerator {

    /**
     * Generates GBNF grammar to constrain model output to valid tool calls:
     * <tool_call>{"name":"...","arguments":{...}}</tool_call>
     * Constrains the tool name to be one of the enabled tool names.
     */
    fun generateStage1(toolNames: List<String>): String {
        val toolNameRules = (toolNames + "none").joinToString(" | ") { "\"\\\"$it\\\"\"" }
        return """
            root ::= "<tool_call>" ws "{" ws "\"name\"" ws ":" ws tool-name ws "," ws "\"arguments\"" ws ":" ws json-object ws "}" ws "</tool_call>"
            tool-name ::= $toolNameRules
            json-object ::= "{" ws (string ":" ws json-value ("," ws string ":" ws json-value)*)? ws "}"
            json-value ::= string | number | json-object | json-array | "true" | "false" | "null"
            string ::= "\"" ([^"\\\] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
            number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
            json-array ::= "[" ws (json-value ("," ws json-value)*)? ws "]"
            ws ::= [ \t\n]*
        """.trimIndent()
    }

    /**
     * Generates GBNF grammar to constrain model output to a specific tool's schema
     * (Stage 2 arguments).
     */
    fun generateStage2(toolName: String, properties: JSONObject): String {
        val keys = properties.keys().asSequence().toList()
        if (keys.isEmpty()) {
            return """
                root ::= "<tool_call>" ws "{" ws "\"name\"" ws ":" ws "\"$toolName\"" ws "," ws "\"arguments\"" ws ":" ws "{" ws "}" ws "}" ws "</tool_call>"
                ws ::= [ \t\n]*
            """.trimIndent()
        }

        val pairRules = mutableListOf<String>()
        val customRules = mutableListOf<String>()

        for (key in keys) {
            val prop = properties.getJSONObject(key)
            val type = prop.optString("type", "string")
            val enumArray = prop.optJSONArray("enum")
            val ruleName = "val-$key"

            if (enumArray != null && enumArray.length() > 0) {
                val enumValues = mutableListOf<String>()
                for (i in 0 until enumArray.length()) {
                    enumValues.add("\"\\\"${enumArray.getString(i)}\\\"\"")
                }
                customRules.add("$ruleName ::= ${enumValues.joinToString(" | ")}")
            } else {
                when (type) {
                    "integer", "int" -> customRules.add("$ruleName ::= integer")
                    "number", "float", "double" -> customRules.add("$ruleName ::= number")
                    "boolean", "bool" -> customRules.add("$ruleName ::= boolean")
                    else -> customRules.add("$ruleName ::= string")
                }
            }

            pairRules.add("\"\\\"$key\\\"\" ws \":\" ws $ruleName")
        }

        val pairsExpr = pairRules.joinToString(" | ")

        val builder = StringBuilder()
        builder.append("root ::= \"<tool_call>\" ws \"{\" ws \"\\\"name\\\"\" ws \":\" ws \"\\\"$toolName\\\"\" ws \",\" ws \"\\\"arguments\\\"\" ws \":\" ws arguments ws \"}\" ws \"</tool_call>\"\n")
        builder.append("arguments ::= \"{\" ws (pair (\",\" ws pair)*)? ws \"}\"\n")
        builder.append("pair ::= $pairsExpr\n")
        for (rule in customRules) {
            builder.append(rule).append("\n")
        }
        builder.append("""
            string ::= "\"" ([^"\\\] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
            integer ::= "-"? [0-9]+
            number ::= "-"? ([0-9] | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
            boolean ::= "true" | "false"
            ws ::= [ \t\n]*
        """.trimIndent())

        return builder.toString()
    }
}
