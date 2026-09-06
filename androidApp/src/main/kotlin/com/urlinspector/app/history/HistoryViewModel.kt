package com.urlinspector.app.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.model.ScanHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val scanRepository: ScanRepository,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<ScanHistoryEntry>>(emptyList())
    val entries: StateFlow<List<ScanHistoryEntry>> = _entries.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _entries.value = scanRepository.getAll()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            scanRepository.deleteById(id)
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            scanRepository.clearAll()
            refresh()
        }
    }
}
