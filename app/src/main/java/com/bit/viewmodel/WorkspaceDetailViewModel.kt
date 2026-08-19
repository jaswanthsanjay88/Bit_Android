package com.bit.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.models.table_schema.WorkspaceEntity
import com.bit.repo.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

@HiltViewModel
class WorkspaceDetailViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _workspaceId = MutableStateFlow<String?>(null)

    private val _workspace = MutableStateFlow<WorkspaceEntity?>(null)
    val workspace: StateFlow<WorkspaceEntity?> = _workspace.asStateFlow()

    private val _files = MutableStateFlow<List<WorkspaceFileEntry>>(emptyList())
    val files: StateFlow<List<WorkspaceFileEntry>> = _files.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _currentArea = MutableStateFlow(WorkspaceStorageArea.FILES)
    val currentArea: StateFlow<WorkspaceStorageArea> = _currentArea.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress: StateFlow<RootfsInstallProgress?> = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError: StateFlow<String?> = _installError.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _availableDistros = MutableStateFlow<List<com.bit.repo.LinuxDistro>>(repository.getAvailableDistros())
    val availableDistros: StateFlow<List<com.bit.repo.LinuxDistro>> = _availableDistros.asStateFlow()

    private val _installedDistro = MutableStateFlow<com.bit.repo.InstalledDistroInfo?>(null)
    val installedDistro: StateFlow<com.bit.repo.InstalledDistroInfo?> = _installedDistro.asStateFlow()

    fun loadWorkspace(id: String) {
        _workspaceId.value = id
        refresh()
    }

    fun refresh() {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            val ws = repository.getById(id)
            _workspace.value = ws
            _availableDistros.value = repository.getAvailableDistros()
            if (ws != null) {
                _installedDistro.value = repository.detectInstalledDistro(ws.root)
                loadFiles()
            }
        }
    }

    fun openDirectory(path: String) {
        _currentPath.value = path
        loadFiles()
    }

    fun goUp() {
        val path = _currentPath.value.trimEnd('/')
        val idx = path.lastIndexOf('/')
        _currentPath.value = if (idx == -1) "" else path.substring(0, idx)
        loadFiles()
    }

    fun switchArea(area: WorkspaceStorageArea) {
        _currentArea.value = area
        _currentPath.value = ""
        loadFiles()
    }

    private fun loadFiles() {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            try {
                val list = repository.listFiles(
                    id = id,
                    path = _currentPath.value,
                    area = _currentArea.value,
                )
                _files.value = list
            } catch (e: Exception) {
                _files.value = emptyList()
            }
        }
    }

    fun installRootfs(url: String) {
        val id = _workspaceId.value ?: return
        _isInstalling.value = true
        _installError.value = null
        viewModelScope.launch {
            try {
                repository.installRootfs(id, url) { progress ->
                    _installProgress.value = progress
                }
                _isInstalling.value = false
                refresh()
            } catch (e: Exception) {
                _isInstalling.value = false
                _installError.value = e.message ?: "Installation failed"
                refresh()
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            repository.setToolApproval(id, toolName, needsApproval)
            refresh()
        }
    }

    suspend fun readText(path: String, area: WorkspaceStorageArea = _currentArea.value): String {
        val id = _workspaceId.value ?: error("No workspace loaded")
        return repository.readText(id, path, area)
    }

    suspend fun writeText(path: String, text: String, overwrite: Boolean = true): WorkspaceFileEntry {
        val id = _workspaceId.value ?: error("No workspace loaded")
        val entry = repository.writeText(id, path, text, overwrite)
        loadFiles()
        return entry
    }

    fun deleteFile(entry: WorkspaceFileEntry) {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            repository.deleteFile(
                id = id,
                path = entry.path,
                recursive = entry.isDirectory,
                area = _currentArea.value,
            )
            loadFiles()
        }
    }

    fun importFile(fileName: String, inputStream: InputStream) {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            repository.importFile(
                id = id,
                destinationPath = _currentPath.value,
                area = _currentArea.value,
                fileName = fileName,
                inputStream = inputStream,
            )
            loadFiles()
        }
    }

    fun importFile(fileName: String, uri: android.net.Uri, onResult: (Result<Unit>) -> Unit = {}) {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            val res = runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open source file for reading")
                input.use { stream ->
                    repository.importFile(
                        id = id,
                        destinationPath = _currentPath.value,
                        area = _currentArea.value,
                        fileName = fileName,
                        inputStream = stream,
                    )
                }
                loadFiles()
            }
            onResult(res)
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            repository.exportFile(
                id = id,
                path = entry.path,
                area = _currentArea.value,
                outputStream = outputStream,
            )
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, uri: android.net.Uri, onResult: (Result<Unit>) -> Unit = {}) {
        val id = _workspaceId.value ?: return
        viewModelScope.launch {
            val res = runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Failed to open destination file for writing")
                output.use { stream ->
                    repository.exportFile(
                        id = id,
                        path = entry.path,
                        area = _currentArea.value,
                        outputStream = stream,
                    )
                }
            }
            onResult(res)
        }
    }
}
