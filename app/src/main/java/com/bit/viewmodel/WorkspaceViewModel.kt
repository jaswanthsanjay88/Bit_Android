package com.bit.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.models.table_schema.WorkspaceEntity
import com.bit.repo.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
) : ViewModel() {

    val workspaces: StateFlow<List<WorkspaceEntity>> = repository.listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    init {
        viewModelScope.launch {
            repository.checkIntegrity()
        }
    }

    fun createWorkspace(name: String, onCreated: (WorkspaceEntity) -> Unit = {}) {
        viewModelScope.launch {
            val entity = repository.create(name)
            onCreated(entity)
        }
    }

    fun deleteWorkspace(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun renameWorkspace(id: String, name: String) {
        viewModelScope.launch {
            repository.rename(id, name)
        }
    }
}
