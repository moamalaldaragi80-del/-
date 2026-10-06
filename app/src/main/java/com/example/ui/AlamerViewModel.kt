package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AlamerRepository
import com.example.model.CallHistoryItem
import com.example.model.ConnectionState
import com.example.model.DiagnosticsReport
import com.example.model.QrValidationResult
import com.example.model.TrustCredentials
import com.example.network.QrParserAndValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AlamerUiState(
    val connectionState: ConnectionState = ConnectionState.UNINITIALIZED,
    val trustCredentials: TrustCredentials? = null,
    val activeCall: CallHistoryItem? = null,
    val callHistory: List<CallHistoryItem> = emptyList(),
    val lastError: String? = null,
    val diagnosticsReport: DiagnosticsReport = DiagnosticsReport(),
    val lastSyncTimestamp: String = "-",
    val isPairingInProgress: Boolean = false,
    val isScanningQr: Boolean = false,
    val isShowingDiagnostics: Boolean = false,
    val qrValidationResult: QrValidationResult? = null,
    val isLanDiscoveryRunning: Boolean = false
)

class AlamerViewModel(
    private val repository: AlamerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlamerUiState())
    val uiState: StateFlow<AlamerUiState> = _uiState.asStateFlow()

    init {
        // Collect repository states
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    connectionState = state,
                    isPairingInProgress = state == ConnectionState.PAIRING
                )
            }
        }

        viewModelScope.launch {
            repository.trustCredentials.collect { trust ->
                _uiState.value = _uiState.value.copy(trustCredentials = trust)
            }
        }

        viewModelScope.launch {
            repository.callManager.activeCall.collect { call ->
                _uiState.value = _uiState.value.copy(activeCall = call)
            }
        }

        viewModelScope.launch {
            repository.callManager.callHistory.collect { history ->
                _uiState.value = _uiState.value.copy(callHistory = history)
            }
        }

        viewModelScope.launch {
            repository.lastError.collect { error ->
                _uiState.value = _uiState.value.copy(lastError = error)
            }
        }

        viewModelScope.launch {
            repository.diagnosticsReport.collect { report ->
                _uiState.value = _uiState.value.copy(diagnosticsReport = report)
            }
        }

        viewModelScope.launch {
            repository.lastSyncTimestamp.collect { sync ->
                _uiState.value = _uiState.value.copy(lastSyncTimestamp = sync)
            }
        }
    }

    /**
     * Parse and validate QR text using the 12-point validator
     */
    fun validateQrText(qrText: String): QrValidationResult {
        val result = QrParserAndValidator.validate(qrText)
        _uiState.value = _uiState.value.copy(qrValidationResult = result)
        return result
    }

    /**
     * Pair using scanned or pasted QR text
     */
    fun pairWithQr(qrText: String) {
        val validation = validateQrText(qrText)
        if (!validation.isValid || validation.data == null) {
            _uiState.value = _uiState.value.copy(
                lastError = validation.errors.firstOrNull() ?: "رمز QR غير صالح"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPairingInProgress = true,
                isScanningQr = false
            )
            val result = repository.pairWithQr(validation.data)
            _uiState.value = _uiState.value.copy(isPairingInProgress = false)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    lastError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    /**
     * Manual / Auto reconnect
     */
    fun reconnect() {
        val trust = repository.trustCredentials.value ?: repository.secureStorage.loadTrust()
        if (trust != null) {
            repository.startAutoReconnect(trust)
        } else {
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.NEEDS_PAIRING,
                isScanningQr = true
            )
        }
    }

    /**
     * 34. Unpair / Reset Trust
     */
    fun unpair() {
        repository.unpair()
    }

    /**
     * Simulate an incoming call for live testing and development
     */
    fun simulateIncomingCall(phoneNumber: String) {
        repository.callManager.onIncomingCallRinging(phoneNumber)
    }

    /**
     * Dismiss the current active call banner
     */
    fun dismissActiveCall() {
        repository.callManager.dismissActiveCall()
    }

    fun openQrScanner() {
        _uiState.value = _uiState.value.copy(isScanningQr = true)
    }

    fun closeQrScanner() {
        _uiState.value = _uiState.value.copy(isScanningQr = false, qrValidationResult = null)
    }

    fun openDiagnostics() {
        _uiState.value = _uiState.value.copy(isShowingDiagnostics = true)
        refreshDiagnostics()
    }

    fun closeDiagnostics() {
        _uiState.value = _uiState.value.copy(isShowingDiagnostics = false)
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            repository.runDiagnostics()
        }
    }

    fun triggerLanDiscovery() {
        val trust = repository.trustCredentials.value ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLanDiscoveryRunning = true)
            val discovered = repository.udpDiscoveryClient.discoverServer(trust.serverId, 2500)
            _uiState.value = _uiState.value.copy(isLanDiscoveryRunning = false)
            if (discovered != null) {
                repository.secureStorage.updateEndpoint(discovered.host, discovered.port, discovered.serverUrl)
                reconnect()
            } else {
                _uiState.value = _uiState.value.copy(
                    lastError = "الجهاز الرئيسي غير متاح على الشبكة الحالية"
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(lastError = null)
    }

    class Factory(private val repository: AlamerRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlamerViewModel(repository) as T
        }
    }
}
