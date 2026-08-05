package com.bit.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bit.data.EmbeddingCache
import com.bit.data.VaultFileStore
import com.bit.database.dao.MemoryNoteDao
import com.bit.engine.EmbeddingEngine
import com.bit.models.table_schema.MemoryNote
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bit.neuron_example.NeuronGraph
import com.bit.neuron_example.RetrievalResult
import com.bit.util.DocumentParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalRagOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingEngine: EmbeddingEngine,
    private val embeddingCache: EmbeddingCache,
    private val vaultStore: VaultFileStore,
    private val memoryNoteDao: MemoryNoteDao
) {
    private val TAG = "GlobalRagOrchestrator"

    private val graphDeferred = CompletableDeferred<NeuronGraph>()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        // Initialize the global graph asynchronously on startup
        scope.launch {
            initializeGlobalGraph()
        }
    }

    private suspend fun initializeGlobalGraph() {
        _isProcessing.value = true
        try {
            embeddingEngine.ensureInitialized(context)
            val graph = NeuronGraph(embeddingEngine = embeddingEngine, embeddingCache = embeddingCache)
            val notes = vaultStore.listAllNotes()
            for (note in notes) {
                graph.addText(note.content, note.title, note.id)
            }
            graphDeferred.complete(graph)
            Log.d(TAG, "Global graph initialized with ${notes.size} notes.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing global graph", e)
            graphDeferred.completeExceptionally(e)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Call this when a note is edited or created to keep the graph in sync.
     * Purges previous chunks for note.id first to prevent duplicate bloat.
     */
    suspend fun reloadNoteIntoGraph(note: MemoryNote) {
        try {
            val graph = graphDeferred.await()
            embeddingEngine.ensureInitialized(context)
            graph.removeSource(note.id)
            graph.addText(note.content, note.title, note.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading note", e)
        }
    }

    /**
     * Call this when a note is deleted to evict its nodes and edges from the graph.
     */
    suspend fun removeNoteFromGraph(noteId: String) {
        try {
            val graph = graphDeferred.await()
            graph.removeSource(noteId)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing note from graph", e)
        }
    }

    suspend fun attachDocument(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        _isProcessing.value = true
        try {
            val mimeType = context.contentResolver.getType(uri)
            val parseResult = DocumentParser.parseDocument(uri, context, mimeType)
            
            if (parseResult.isFailure) {
                return@withContext Result.failure(parseResult.exceptionOrNull() ?: Exception("Failed to parse"))
            }

            val content = parseResult.getOrThrow()
            
            // Extract filename from URI
            var name = "Attached Document"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
            
            // Add extracted document text directly to the RAG graph (skipping vault note creation)
            val graph = graphDeferred.await()
            embeddingEngine.ensureInitialized(context)
            val docId = java.util.UUID.randomUUID().toString()
            graph.addText(text = content, sourceName = name, sourceId = docId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching document", e)
            Result.failure(e)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Rebuild the in-memory graph from the vault files on disk.
     * Used after bulk operations that write .md files directly (e.g. backup restore)
     * so the graph never serves stale or empty knowledge.
     */
    suspend fun rebuildFromDisk() {
        try {
            val graph = graphDeferred.await()
            embeddingEngine.ensureInitialized(context)
            val notes = vaultStore.listAllNotes()
            graph.clear()
            for (note in notes) {
                graph.addText(note.content, note.title, note.id)
            }
            Log.d(TAG, "Rebuilt global graph from disk with ${notes.size} notes.")
        } catch (e: Exception) {
            Log.e(TAG, "Error rebuilding global graph from disk", e)
        }
    }

    suspend fun queryGlobalKnowledge(query: String, topK: Int = 5): RetrievalResult? = withContext(Dispatchers.IO) {
        try {
            val graph = graphDeferred.await()
            embeddingEngine.ensureInitialized(context)
            graph.queryWithPipeline(query, topK)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying attached document", e)
            null
        }
    }
}
