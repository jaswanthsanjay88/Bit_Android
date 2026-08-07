package com.bit.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.data.AiMemoryWriter
import com.bit.models.table_schema.MemoryNote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class ImportEntry(
    val id: String = UUID.randomUUID().toString(),
    val category: String,
    val dateText: String,
    val parsedDate: Long,
    val content: String,
    var isSelected: Boolean = true
)

enum class ImportStep {
    PROMPT, PASTE, PREVIEW, SUCCESS, ERROR
}

@HiltViewModel
class MemoryImportViewModel @Inject constructor(
    private val aiMemoryWriter: AiMemoryWriter
) : ViewModel() {

    private val _currentStep = MutableStateFlow(ImportStep.PROMPT)
    val currentStep: StateFlow<ImportStep> = _currentStep.asStateFlow()

    private val _pastedText = MutableStateFlow("")
    val pastedText: StateFlow<String> = _pastedText.asStateFlow()

    private val _parsedEntries = MutableStateFlow<List<ImportEntry>>(emptyList())
    val parsedEntries: StateFlow<List<ImportEntry>> = _parsedEntries.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun updatePastedText(text: String) {
        _pastedText.value = text
        if (_errorMessage.value != null) {
            _errorMessage.value = null
        }
    }

    fun setStep(step: ImportStep) {
        _currentStep.value = step
    }

    fun parsePastedText() {
        val text = _pastedText.value
        
        // 1. Strip outer code fences if they exist
        val cleanText = text.replace(Regex("^```[a-zA-Z]*\\n|```$"), "").trim()

        if (cleanText.isEmpty()) {
            _errorMessage.value = "Couldn't read this format — make sure you pasted the full response."
            _currentStep.value = ImportStep.ERROR
            return
        }

        val entries = mutableListOf<ImportEntry>()
        val lines = cleanText.lines()
        var currentCategory = "Unknown"

        val categoryRegex = Regex("^##\\s+(.+)$")
        val entryRegex = Regex("^\\[(\\d{4}-\\d{2}-\\d{2}|unknown)\\]\\s*-\\s*(.+)$")


        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val categoryMatch = categoryRegex.find(trimmedLine)
            if (categoryMatch != null) {
                currentCategory = categoryMatch.groupValues[1].trim()
                continue
            }

            val entryMatch = entryRegex.find(trimmedLine)
            if (entryMatch != null) {
                val dateStr = entryMatch.groupValues[1]
                val content = entryMatch.groupValues[2].trim()


                    val parsedDate = if (dateStr == "unknown") {
                        System.currentTimeMillis()
                    } else {
                        try {
                            dateFormatter.parse(dateStr)?.time ?: System.currentTimeMillis()
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        }
                    }

                    entries.add(
                        ImportEntry(
                            category = currentCategory,
                            dateText = dateStr,
                            parsedDate = parsedDate,
                            content = content
                        )
                    )
            }
        }

        if (entries.isEmpty()) {
            _errorMessage.value = "No valid memory entries found. Make sure you pasted the full response including the code block."
            _currentStep.value = ImportStep.ERROR
        } else {
            _parsedEntries.value = entries
            _currentStep.value = ImportStep.PREVIEW
        }
    }

    fun toggleEntrySelection(id: String) {
        _parsedEntries.value = _parsedEntries.value.map {
            if (it.id == id) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun importSelectedMemories() {
        viewModelScope.launch {
            val selectedEntries = _parsedEntries.value.filter { it.isSelected }
            
            for (entry in selectedEntries) {
                aiMemoryWriter.importMemory(
                    text = entry.content,
                    category = entry.category,
                    parsedDate = entry.parsedDate
                )
            }
            
            // UI will handle refresh upon SUCCESS state
            
            _currentStep.value = ImportStep.SUCCESS
        }
    }

    fun reset() {
        _currentStep.value = ImportStep.PROMPT
        _pastedText.value = ""
        _parsedEntries.value = emptyList()
        _errorMessage.value = null
    }
}
