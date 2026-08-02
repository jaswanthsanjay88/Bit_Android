package com.bit.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppForegroundTracker {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    val isInForeground: Boolean
        get() = _foreground.value

    fun setForeground(inForeground: Boolean) {
        _foreground.value = inForeground
    }
}
