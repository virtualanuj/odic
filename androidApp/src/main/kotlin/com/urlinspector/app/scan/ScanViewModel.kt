package com.urlinspector.app.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Loading : ScanUiState
    data class Success(val result: ScanResult) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(
    private val scanUrlUseCase: ScanUrlUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun scan(rawUrl: String) {
        _uiState.value = ScanUiState.Loading
        viewModelScope.launch {
            _uiState.value = try {
                ScanUiState.Success(scanUrlUseCase.scan(rawUrl))
            } catch (e: Exception) {
                ScanUiState.Error(e.message ?: "Invalid URL")
            }
        }
    }

    fun reset() {
        _uiState.value = ScanUiState.Idle
    }
}
