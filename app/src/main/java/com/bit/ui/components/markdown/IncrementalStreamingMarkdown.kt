package com.bit.ui.components.markdown

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Snapshot representing parsed markdown content split into stable (completed) blocks
 * and the live tail block receiving character updates.
 */
data class StreamingSnapshot(
    val stableBlocks: List<String> = emptyList(),
    val liveBlock: String = "",
    val fullText: String = "",
    val isStreaming: Boolean = true
)

/**
 * Two-buffer latest-wins streaming renderer state.
 * Offscreen worker thread parses incoming text chunks without blocking Compose UI.
 */
class StreamingMarkdownRenderState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshotFlow = MutableStateFlow(StreamingSnapshot())
    val snapshotFlow: StateFlow<StreamingSnapshot> = _snapshotFlow.asStateFlow()

    private val inputFlow = MutableSharedFlow<Pair<String, Boolean>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var workerJob: Job? = null

    fun start() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            var lastEmittedText = ""
            val fadeWindowMs = 90L

            inputFlow.collect { (rawContent, isStreaming) ->
                if (rawContent == lastEmittedText && isStreaming == _snapshotFlow.value.isStreaming) return@collect
                lastEmittedText = rawContent

                val preprocessed = MarkdownPreprocessor.toRenderableMarkdownText(rawContent)
                val snapshot = parseIntoSnapshot(preprocessed, rawContent, isStreaming)

                _snapshotFlow.value = snapshot

                if (isStreaming) {
                    delay(fadeWindowMs) // latest-wins window
                }
            }
        }
    }

    fun offer(content: String, isStreaming: Boolean) {
        inputFlow.tryEmit(Pair(content, isStreaming))
    }

    fun close() {
        scope.cancel()
    }

    private fun parseIntoSnapshot(preprocessed: String, rawText: String, isStreaming: Boolean): StreamingSnapshot {
        val paragraphs = splitMarkdownBlocks(preprocessed)

        return if (paragraphs.isEmpty()) {
            StreamingSnapshot(fullText = rawText, isStreaming = isStreaming)
        } else if (!isStreaming || paragraphs.size == 1) {
            StreamingSnapshot(
                stableBlocks = if (!isStreaming) paragraphs else emptyList(),
                liveBlock = if (isStreaming) paragraphs.last() else "",
                fullText = rawText,
                isStreaming = isStreaming
            )
        } else {
            val stable = paragraphs.dropLast(1)
            val live = paragraphs.last()
            StreamingSnapshot(
                stableBlocks = stable,
                liveBlock = live,
                fullText = rawText,
                isStreaming = isStreaming
            )
        }
    }

    private fun splitMarkdownBlocks(text: String): List<String> {
        val lines = text.lines()
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var insideCode = false

        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                insideCode = !insideCode
            }
            if (!insideCode && line.isBlank()) {
                if (current.isNotBlank()) {
                    blocks.add(current.toString().trim())
                    current.clear()
                }
            } else {
                if (current.isNotEmpty()) current.append("\n")
                current.append(line)
            }
        }
        if (current.isNotBlank()) {
            blocks.add(current.toString().trim())
        }
        return blocks
    }
}

/**
 * Two-buffer streaming Markdown Composable with graphics-layer alpha fade.
 */
@Composable
fun IncrementalStreamingMarkdownView(
    state: StreamingMarkdownRenderState,
    modifier: Modifier = Modifier,
    renderBlock: @Composable (String) -> Unit
) {
    val snapshot by state.snapshotFlow.collectAsStateWithLifecycle()
    val alphaAnim = remember { Animatable(0.85f) }

    LaunchedEffect(state) {
        state.start()
    }

    DisposableEffect(state) {
        onDispose { state.close() }
    }

    LaunchedEffect(snapshot.stableBlocks.size, snapshot.liveBlock) {
        alphaAnim.snapTo(0.7f)
        alphaAnim.animateTo(1.0f, animationSpec = tween(90))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = alphaAnim.value }
    ) {
        snapshot.stableBlocks.forEach { block ->
            renderBlock(block)
            Spacer(Modifier.height(8.dp))
        }

        if (snapshot.liveBlock.isNotEmpty()) {
            renderBlock(snapshot.liveBlock)
        }
    }
}
