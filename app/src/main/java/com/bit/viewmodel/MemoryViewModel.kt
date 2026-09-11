package com.bit.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.worker.RagVaultIntegration
import com.bit.worker.ScoredVaultContent
import com.bit.worker.VaultStatsInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.bit.database.dao.MemoryNoteDao
import com.bit.models.table_schema.MemoryNote
import com.bit.data.AiMemoryWriter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val ragVaultIntegration: RagVaultIntegration,
    private val memoryNoteDao: MemoryNoteDao,
    private val aiMemoryWriter: AiMemoryWriter,
    private val vaultFileStore: com.bit.data.VaultFileStore
) : ViewModel() {

    companion object {
        private const val TAG = "MemoryViewModel"
    }

    private val _isMemoryEnabled = MutableStateFlow(true)
    val isMemoryEnabled: StateFlow<Boolean> = _isMemoryEnabled.asStateFlow()

    private val _memoryResults = MutableStateFlow<List<ScoredVaultContent>>(emptyList())
    val memoryResults: StateFlow<List<ScoredVaultContent>> = _memoryResults.asStateFlow()

    private val _vaultStats = MutableStateFlow<VaultStatsInfo?>(null)
    val vaultStats: StateFlow<VaultStatsInfo?> = _vaultStats.asStateFlow()

    private val _showMemoryOverlay = MutableStateFlow(false)
    val showMemoryOverlay: StateFlow<Boolean> = _showMemoryOverlay.asStateFlow()

    val allNotes: StateFlow<List<MemoryNote>> = memoryNoteDao.getAllNotesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<MemoryNote>> = allNotes.map { notes ->
        notes.filter { it.noteType == "document" || it.folder == "documents" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val facts: StateFlow<List<MemoryNote>> = allNotes.map { notes ->
        notes.filter { it.noteType == "fact" || it.noteType == "ai_fact" || it.folder == "ai_memory" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _memoryEntryCount = MutableStateFlow(0)
    val memoryEntryCount: StateFlow<Int> = _memoryEntryCount.asStateFlow()

    @Volatile private var isVaultInitialized = false

    init {
        initializeVault()
        refreshNotesFromDisk()
    }

    private fun initializeVault() {
        viewModelScope.launch {
            try {
                ragVaultIntegration.initialize()
                isVaultInitialized = true
                refreshStats()
                Log.d(TAG, "Memory vault initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize memory vault", e)
            }
        }
    }

    fun refreshNotesFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileNotes = vaultFileStore.listAllNotes()
                for (note in fileNotes) {
                    val noteForDb = if (note.content.length > 50_000) {
                        note.copy(content = note.content.take(50_000) + "\n\n...[Full document stored in vault on disk]...")
                    } else {
                        note
                    }
                    try { memoryNoteDao.insertNote(noteForDb) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing notes from disk in MemoryViewModel", e)
            }
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        _isMemoryEnabled.value = enabled
        if (enabled && !isVaultInitialized) {
            initializeVault()
        }
    }

    fun toggleMemoryOverlay() {
        val next = !_showMemoryOverlay.value
        _showMemoryOverlay.value = next
        if (next) {
            refreshNotesFromDisk()
        }
    }

    fun dismissMemoryOverlay() {
        _showMemoryOverlay.value = false
    }

    fun addFact(factText: String) {
        val trimmed = factText.trim()
        if (trimmed.isNotBlank()) {
            aiMemoryWriter.saveAiMemory(text = trimmed)
        }
    }

    fun deleteNote(note: MemoryNote) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (note.filePath.isNotBlank()) {
                    val f = java.io.File(note.filePath)
                    if (f.exists()) f.delete()
                }
                memoryNoteDao.deleteNoteById(note.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting note ${note.id}", e)
            }
        }
    }

    fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                refreshNotesFromDisk()
                val stats = ragVaultIntegration.getVaultStats()
                _vaultStats.value = stats
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing vault stats", e)
            }
        }
    }
}
