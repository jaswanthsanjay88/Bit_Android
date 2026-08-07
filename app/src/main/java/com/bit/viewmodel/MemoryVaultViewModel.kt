package com.bit.viewmodel

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.data.AiMemoryWriter
import com.bit.data.VaultFileStore
import com.bit.database.dao.MemoryNoteDao
import com.bit.global.AppPaths
import com.bit.models.table_schema.MemoryNote
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltViewModel
class MemoryVaultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultFileStore: VaultFileStore,
    private val aiMemoryWriter: AiMemoryWriter,
    private val memoryNoteDao: MemoryNoteDao,
    private val globalRagOrchestrator: com.bit.worker.GlobalRagOrchestrator,
    private val appSettingsDataStore: com.bit.data.AppSettingsDataStore
) : ViewModel() {

    private val TAG = "MemoryVaultVM"
    private val deletedNoteIds = mutableSetOf<String>()

    val hasSeenMemoryImportPrompt = appSettingsDataStore.hasSeenMemoryImportPrompt.stateIn(
        viewModelScope, SharingStarted.Eagerly, true
    )

    fun markMemoryImportPromptSeen() {
        viewModelScope.launch {
            appSettingsDataStore.saveHasSeenMemoryImportPrompt(true)
        }
    }

    private val _notes = MutableStateFlow<List<MemoryNote>>(emptyList())
    val notes: StateFlow<List<MemoryNote>> = _notes.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _selectedCategory = MutableStateFlow("all")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    val filteredNotes: StateFlow<List<MemoryNote>> = combine(
        _notes,
        _searchQuery,
        _selectedTag,
        _selectedCategory
    ) { notesList, query, tag, category ->
        notesList.filter { note ->
            val matchesCategory = when (category) {
                "all" -> true
                "facts" -> note.folder == "ai_memory" || note.noteType == "fact"
                "notes" -> note.folder == "notes" || note.noteType == "note"
                "tasks" -> note.noteType == "task"
                "documents" -> note.folder == "documents" || note.noteType == "document"
                else -> true
            }
            val matchesQuery = query.isBlank() ||
                    note.title.contains(query, ignoreCase = true) ||
                    note.content.contains(query, ignoreCase = true)
            val matchesTag = tag.isNullOrBlank() || note.tags.split(",").map { it.trim() }.contains(tag)

            matchesCategory && matchesQuery && matchesTag
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conflicts: StateFlow<List<MemoryNote>> = _notes.let { notesFlow ->
        combine(notesFlow) { noteArrays ->
            noteArrays.firstOrNull()?.filter { !it.conflictWithFactId.isNullOrBlank() } ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    init {
        refreshNotesFromDisk()
    }

    fun refreshNotesFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val fileNotes = vaultFileStore.listAllNotes()
            _notes.value = fileNotes
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSelectedTag(tag: String?) { _selectedTag.value = tag }
    fun setSelectedCategory(category: String) { _selectedCategory.value = category }

    /**
     * Save a note to disk (source of truth). Returns the saved MemoryNote with
     * filePath populated so the caller can track it for future edits.
     */
    fun saveNote(
        title: String,
        content: String,
        tags: String = "",
        noteType: String = "note",
        folder: String = "notes",
        status: String = "todo",
        dueDate: Long? = null,
        isAiMemoryEnabled: Boolean = true,
        existingId: String? = null,
        filePath: String = "",
        onSaved: ((MemoryNote) -> Unit)? = null
    ) {
        if (existingId != null && deletedNoteIds.contains(existingId)) return
        viewModelScope.launch(Dispatchers.IO) {
            if (existingId != null && deletedNoteIds.contains(existingId)) return@launch
            val now = System.currentTimeMillis()
            val existing = if (!existingId.isNullOrBlank()) {
                _notes.value.find { it.id == existingId }
            } else null

            val noteToWrite = existing?.copy(
                title = title,
                content = content,
                tags = tags,
                noteType = noteType,
                folder = folder,
                status = status,
                dueDate = dueDate,
                isAiMemoryEnabled = isAiMemoryEnabled,
                updatedAt = now
            ) ?: MemoryNote(
                id = existingId ?: java.util.UUID.randomUUID().toString(),
                title = title,
                content = content,
                tags = tags,
                noteType = noteType,
                folder = folder,
                status = status,
                dueDate = dueDate,
                filePath = filePath,
                isAiMemoryEnabled = isAiMemoryEnabled,
                createdAt = now,
                updatedAt = now
            )

            val written = vaultFileStore.writeNote(noteToWrite)
            try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
            globalRagOrchestrator.reloadNoteIntoGraph(written)
            refreshNotesFromDisk()

            withContext(Dispatchers.Main) {
                onSaved?.invoke(written)
            }
        }
    }

    fun updateTaskStatus(note: MemoryNote, newStatus: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val updated = note.copy(
                status = newStatus,
                completedAt = if (newStatus == "done") now else null,
                updatedAt = now
            )
            val written = vaultFileStore.writeNote(updated)
            try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
            globalRagOrchestrator.reloadNoteIntoGraph(written)

            if (newStatus == "done") {
                val aiNote = aiMemoryWriter.saveAiMemory(
                    text = "User completed task '${note.title}'",
                    title = "Task completed: ${note.title}"
                )
                // Keep Room and the RAG graph in sync with the new file on disk
                try { memoryNoteDao.insertNote(aiNote) } catch (_: Exception) {}
                globalRagOrchestrator.reloadNoteIntoGraph(aiNote)
            }
            refreshNotesFromDisk()
        }
    }

    fun pinNote(note: MemoryNote) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(isPinned = !note.isPinned, updatedAt = System.currentTimeMillis())
            val written = vaultFileStore.writeNote(updated)
            try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
            refreshNotesFromDisk()
        }
    }

    fun moveAiFactToNotes(note: MemoryNote) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete old file, write to notes folder
            vaultFileStore.deleteNote(note)
            val updated = note.copy(folder = "notes", noteType = "note", filePath = "", updatedAt = System.currentTimeMillis())
            val written = vaultFileStore.writeNote(updated)
            try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
            globalRagOrchestrator.reloadNoteIntoGraph(written)
            refreshNotesFromDisk()
        }
    }

    fun toggleNoteMemory(note: MemoryNote) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = note.copy(isAiMemoryEnabled = !note.isAiMemoryEnabled, updatedAt = System.currentTimeMillis())
            val written = vaultFileStore.writeNote(updated)
            try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
            refreshNotesFromDisk()
        }
    }

    fun resolveConflict(userNote: MemoryNote, option: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val conflictingNote = userNote.conflictWithFactId?.let { cid ->
                _notes.value.find { it.id == cid }
            }
            when (option) {
                "mine" -> {
                    if (conflictingNote != null) {
                        vaultFileStore.deleteNote(conflictingNote)
                        try { memoryNoteDao.deleteNote(conflictingNote) } catch (_: Exception) {}
                        globalRagOrchestrator.removeNoteFromGraph(conflictingNote.id)
                    }
                    val updated = userNote.copy(conflictWithFactId = null)
                    val written = vaultFileStore.writeNote(updated)
                    try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
                    globalRagOrchestrator.reloadNoteIntoGraph(written)
                }
                "ai" -> {
                    vaultFileStore.deleteNote(userNote)
                    try { memoryNoteDao.deleteNote(userNote) } catch (_: Exception) {}
                    globalRagOrchestrator.removeNoteFromGraph(userNote.id)
                    if (conflictingNote != null) {
                        val updated = conflictingNote.copy(conflictWithFactId = null)
                        val written = vaultFileStore.writeNote(updated)
                        try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
                        globalRagOrchestrator.reloadNoteIntoGraph(written)
                    }
                }
                "both" -> {
                    val updated = userNote.copy(conflictWithFactId = null)
                    val written = vaultFileStore.writeNote(updated)
                    try { memoryNoteDao.insertNote(written) } catch (_: Exception) {}
                    globalRagOrchestrator.reloadNoteIntoGraph(written)
                }
            }
            refreshNotesFromDisk()
        }
    }

    fun deleteNote(note: MemoryNote) {
        deletedNoteIds.add(note.id)
        // Optimistically remove from in-memory state immediately so UI updates instantly
        _notes.value = _notes.value.filter { it.id != note.id }
        // Then perform actual disk/DB/graph deletion in background
        viewModelScope.launch(Dispatchers.IO) {
            vaultFileStore.deleteNote(note)
            try { memoryNoteDao.deleteNoteById(note.id) } catch (_: Exception) {}
            try { memoryNoteDao.deleteNote(note) } catch (_: Exception) {}
            globalRagOrchestrator.removeNoteFromGraph(note.id)
            // Refresh from disk to confirm sync
            refreshNotesFromDisk()
        }
    }

    fun exportVaultBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val backupFile = File(downloadsDir, "BIT_Vault_Backup_${System.currentTimeMillis()}.zip")
                val vaultFolder = AppPaths.vaultRoot(context)

                ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
                    vaultFolder.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            zos.putNextEntry(ZipEntry(file.relativeTo(vaultFolder).path))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Exported vault backup to Downloads", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importVaultBackup(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val vaultFolder = AppPaths.vaultRoot(context)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    java.util.zip.ZipInputStream(inputStream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val file = File(vaultFolder, entry.name)
                            if (entry.isDirectory) {
                                file.mkdirs()
                            } else {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                refreshNotesFromDisk()
                // Reconcile Room + RAG graph with the restored files on disk so the
                // filesystem stays the single source of truth (no stale/empty memory)
                try {
                    memoryNoteDao.deleteAll()
                    vaultFileStore.listAllNotes().forEach { note ->
                        memoryNoteDao.insertNote(note)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-sync Room after restore", e)
                }
                globalRagOrchestrator.rebuildFromDisk()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Successfully restored vault backup!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Import failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
