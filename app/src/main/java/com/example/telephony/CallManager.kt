package com.example.telephony

import com.example.model.CallHistoryItem
import com.example.model.CallRecordRequest
import com.example.model.CallerCustomerContext
import com.example.network.SignalRClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Manages call lifecycle, stable CallId, idempotency, customer context enrichment,
 * and offline buffer dispatching.
 */
class CallManager(
    private val signalRClient: SignalRClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active incoming call state
    private val _activeCall = MutableStateFlow<CallHistoryItem?>(null)
    val activeCall: StateFlow<CallHistoryItem?> = _activeCall.asStateFlow()

    // Call history list (most recent first)
    private val _callHistory = MutableStateFlow<List<CallHistoryItem>>(emptyList())
    val callHistory: StateFlow<List<CallHistoryItem>> = _callHistory.asStateFlow()

    // Processed CallIds set to guarantee IDEMPOTENCY (Rule 19)
    private val processedCallIds = mutableSetOf<String>()

    // Offline buffer for calls queued when network was disconnected (Rule 27)
    private val offlineQueue = mutableListOf<CallRecordRequest>()

    // Current active call ID
    @Volatile
    private var currentActiveCallId: String? = null

    // V106 HTTP Fallback Dispatcher (Rule 8)
    var httpFallbackDispatcher: ((CallRecordRequest) -> Unit)? = null

    init {
        // Listen to CustomerContext responses from SignalR
        scope.launch {
            signalRClient.customerContextEvents.collect { context ->
                handleIncomingCustomerContext(context)
            }
        }
    }

    /**
     * Called when phone rings or when manual simulation is triggered.
     * Enforces single stable CallId and idempotency.
     */
    fun onIncomingCallRinging(rawPhoneNumber: String): String {
        val normalized = normalizePhoneNumber(rawPhoneNumber)

        // If this is an existing active call for the same number ringing repeatedly, keep stable CallId
        val existingActive = _activeCall.value
        if (existingActive != null && existingActive.normalizedPhone == normalized) {
            return existingActive.callId
        }

        // Generate stable CallId once (Rule 17)
        val newCallId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val utcIsoTime = formatIsoUtc(Date(nowMs))

        val callItem = CallHistoryItem(
            callId = newCallId,
            phone = rawPhoneNumber.ifBlank { "غير معروف" },
            normalizedPhone = normalized,
            direction = "incoming",
            startedAtUtc = utcIsoTime,
            timestampMs = nowMs,
            isDispatchedToTaloola = false,
            customerContext = null
        )

        currentActiveCallId = newCallId
        processedCallIds.add(newCallId)
        _activeCall.value = callItem
        _callHistory.value = listOf(callItem) + _callHistory.value.take(49)

        // Dispatch to Taloola via SignalR (Rule 18)
        val request = CallRecordRequest(
            callId = newCallId,
            phone = callItem.phone,
            normalizedPhone = normalized,
            direction = "incoming",
            startedAtUtc = utcIsoTime
        )

        dispatchCallRecord(request)
        return newCallId
    }

    /**
     * Dispatch or buffer if offline (Rule 27 / V106 Rule 8)
     */
    private fun dispatchCallRecord(request: CallRecordRequest) {
        val sent = signalRClient.recordCallerCall(request)
        if (sent) {
            markCallDispatched(request.callId)
        } else {
            // V106: Try HTTP fallback immediately when SignalR is unavailable
            httpFallbackDispatcher?.invoke(request)

            // Buffer offline for SignalR flush
            synchronized(offlineQueue) {
                if (offlineQueue.none { it.callId == request.callId }) {
                    offlineQueue.add(request)
                }
            }
        }
    }

    /**
     * Flush offline queue when SignalR reconnects
     */
    fun onSignalRConnected() {
        scope.launch {
            val toSend = synchronized(offlineQueue) {
                val list = ArrayList(offlineQueue)
                offlineQueue.clear()
                list
            }

            for (req in toSend) {
                val success = signalRClient.recordCallerCall(req)
                if (success) {
                    markCallDispatched(req.callId)
                } else {
                    synchronized(offlineQueue) {
                        offlineQueue.add(req)
                    }
                }
            }
        }
    }

    private fun markCallDispatched(callId: String) {
        val current = _activeCall.value
        if (current?.callId == callId) {
            _activeCall.value = current.copy(isDispatchedToTaloola = true)
        }
        _callHistory.value = _callHistory.value.map {
            if (it.callId == callId) it.copy(isDispatchedToTaloola = true) else it
        }
    }

    /**
     * Handles customer context returned from Taloola
     */
    private fun handleIncomingCustomerContext(context: CallerCustomerContext) {
        val targetCallId = context.callId ?: currentActiveCallId

        if (targetCallId != null) {
            val active = _activeCall.value
            if (active != null && (active.callId == targetCallId || active.normalizedPhone == context.normalizedPhone)) {
                _activeCall.value = active.copy(customerContext = context)
            }

            _callHistory.value = _callHistory.value.map { item ->
                if (item.callId == targetCallId || (item.normalizedPhone == context.normalizedPhone && item.customerContext == null)) {
                    item.copy(customerContext = context)
                } else {
                    item
                }
            }
        }
    }

    fun dismissActiveCall() {
        _activeCall.value = null
        currentActiveCallId = null
    }

    fun clearHistory() {
        _callHistory.value = emptyList()
        _activeCall.value = null
        currentActiveCallId = null
        processedCallIds.clear()
    }

    /**
     * Normalizes phone number (strips formatting characters, keeps digits and optional leading +)
     */
    fun normalizePhoneNumber(phone: String): String {
        val trimmed = phone.trim()
        val digitsOnly = trimmed.filter { it.isDigit() || it == '+' }
        return if (digitsOnly.isNotBlank()) digitsOnly else phone.filter { it.isDigit() }
    }

    private fun formatIsoUtc(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
}
