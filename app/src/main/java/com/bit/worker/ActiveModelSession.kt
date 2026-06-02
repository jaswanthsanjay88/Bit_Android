package com.bit.worker

import com.bit.models.enums.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveModelSession {
    private val _currentModelId = MutableStateFlow("")
    val currentModelId: StateFlow<String> = _currentModelId.asStateFlow()

    private val _currentModelType = MutableStateFlow<ProviderType?>(null)
    val currentModelType: StateFlow<ProviderType?> = _currentModelType.asStateFlow()

    fun set(modelId: String, type: ProviderType) {
        _currentModelId.value = modelId
        _currentModelType.value = type
    }

    fun clear() {
        _currentModelId.value = ""
        _currentModelType.value = null
    }
}
