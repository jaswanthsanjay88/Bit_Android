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

    private val _isImageModel = MutableStateFlow(false)
    val isImageModel: StateFlow<Boolean> = _isImageModel.asStateFlow()

    fun set(modelId: String, type: ProviderType, isImage: Boolean = false) {
        _currentModelId.value = modelId
        _currentModelType.value = type
        _isImageModel.value = isImage || type == ProviderType.DIFFUSION
    }

    fun setIsImageModel(isImage: Boolean) {
        _isImageModel.value = isImage
    }

    fun clear() {
        _currentModelId.value = ""
        _currentModelType.value = null
        _isImageModel.value = false
    }
}
