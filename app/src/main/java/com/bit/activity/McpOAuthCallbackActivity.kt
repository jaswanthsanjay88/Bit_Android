package com.bit.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bit.mcp.McpOAuthCallbackHub
import com.bit.mcp.McpOAuthResult

/**
 * Transparent Activity that receives deep link redirects from OAuth 2.1 authorization flows
 * (e.g. bit://oauth/callback?code=...&state=...).
 */
class McpOAuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            val state = uri.getQueryParameter("state")
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            val errorDesc = uri.getQueryParameter("error_description")

            if (!code.isNullOrBlank() && !state.isNullOrBlank()) {
                McpOAuthCallbackHub.emit(McpOAuthResult.Success(state = state, code = code))
            } else if (!error.isNullOrBlank()) {
                McpOAuthCallbackHub.emit(
                    McpOAuthResult.Error(
                        state = state,
                        error = error,
                        errorDescription = errorDesc
                    )
                )
            }
        }
        finish()
    }
}
