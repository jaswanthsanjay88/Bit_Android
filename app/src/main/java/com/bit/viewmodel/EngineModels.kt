package com.bit.viewmodel

import android.app.Application
import android.content.Context
import com.bit.api.local.LocalProvider
import com.bit.data.MemoryManager
import com.bit.data.repository.ConversationRepository
import com.bit.data.repository.SettingsRepository
import com.bit.model.ChatMessage
import com.bit.sandbox.SandboxManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

data class GenerationContext(
    val automationToolsEnabled: Boolean = false,
    val foregroundServiceManagedExternally: Boolean = false
)

data class GenerationConfig(
    val modelId: String = ""
)

class ProviderRegistry {
    val all: List<Any> = emptyList()
    suspend fun awaitInitialSync() {}
    fun providerForModel(modelId: String): String = "default"
    fun isConfigured(providerName: String, apiKey: String): Boolean = true
}

class RagManager(
    conversations: ConversationRepository,
    settings: SettingsRepository,
    localProvider: LocalProvider,
    appContext: Context,
    scope: CoroutineScope,
    emitSnackbar: (String) -> Unit
)

data class GenerationCallbacks(
    val onStreamUpdate: (String) -> Unit = {},
    val onLoadingChange: (Boolean) -> Unit = {},
    val onStreamClear: () -> Unit = {},
    val isLatestPersist: () -> Boolean = { true }
)

class GenerationManager(
    app: Application,
    conversations: ConversationRepository,
    memoryManager: MemoryManager,
    providers: List<Any>,
    context: Context,
    sandboxFactory: SandboxManagerFactory?
) {
    var onConfirmShellCommand: ((String, String) -> Boolean)? = null

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        runId: String,
        pass: Int,
        config: GenerationConfig,
        ctx: GenerationContext,
        generationJob: Any?,
        callbacks: GenerationCallbacks
    ) {
    }
}

object ConversationUiState {
    fun resolvePath(
        allMessages: List<ChatMessage>,
        streamingMsg: ChatMessage?,
        selectedChildren: Map<String, String>
    ): List<ChatMessage> = allMessages
}

class GenerationRequestBuilder(
    private val settings: SettingsRepository,
    private val convRepo: ConversationRepository,
    private val memoryManager: MemoryManager,
    private val providerRegistry: ProviderRegistry,
    private val ragManager: RagManager,
    private val appContext: Context,
    private val pendingConversationSettings: StateFlow<Any?>,
    private val onSnackbar: (String) -> Unit
) {
    data class ResolvedPrompt(
        val system: String?,
        val userPrepend: String?,
        val userPostpend: String?
    )

    suspend fun buildEffectiveSystemPrompt(conversationId: String, effectiveModelId: String): ResolvedPrompt {
        return ResolvedPrompt(null, null, null)
    }

    suspend fun buildEffectiveConversationSettings(conversationId: String): Any {
        return Any()
    }

    suspend fun buildGenerationPair(
        providerName: String,
        effectiveModelId: String,
        activeKey: String,
        systemPrompt: String?,
        userPrepend: String?,
        userPostpend: String?,
        effectiveSettings: Any,
        conversationId: String
    ): Pair<GenerationConfig, GenerationContext> {
        return Pair(GenerationConfig(effectiveModelId), GenerationContext())
    }
}
