package com.bit.data.repository

import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val exactExecutionEnabled: StateFlow<Boolean>
    val selectedModel: StateFlow<String>
    suspend fun awaitInitialLoad()
    suspend fun awaitActiveKey(providerName: String): String?
    fun resolveActiveKey(providerName: String): String?
}
