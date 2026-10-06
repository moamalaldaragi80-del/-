package com.example.network

import com.example.model.CallRecordRequest
import com.example.model.CallerCustomerContext
import com.example.model.PosHubState
import com.example.model.SignalRAuthRequest
import com.example.model.TrustCredentials
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central POS Hub Connection Manager (Sections 4, 5, 6, 7, 8, 9, 10, 11, 15, 18, 32).
 * Singleton application-scoped manager that exclusively creates, monitors,
 * and maintains the realtime SignalR hub connection to /posHub.
 */
class PosHubConnectionManager(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive for WebSockets
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "ALAMER_POSHUB"
        private const val RECORD_SEPARATOR = "\u001e"

        @Volatile
        private var instance: PosHubConnectionManager? = null

        fun getInstance(okHttpClient: OkHttpClient? = null): PosHubConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: PosHubConnectionManager(okHttpClient ?: OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(15, TimeUnit.SECONDS)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .build()
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val authAdapter = moshi.adapter(SignalRAuthRequest::class.java)
    private val callRecordAdapter = moshi.adapter(CallRecordRequest::class.java)
    private val customerContextAdapter = moshi.adapter(CallerCustomerContext::class.java)

    // State Machine
    private val _connectionState = MutableStateFlow(PosHubState.DISCONNECTED)
    val connectionState: StateFlow<PosHubState> = _connectionState.asStateFlow()

    // Concurrency controls (Section 5 & 9)
    private val connectionMutex = Mutex()
    private val currentGeneration = AtomicInteger(0)
    private val invocationCounter = AtomicInteger(1)

    // Current connection target & credentials
    @Volatile
    private var activeTrust: TrustCredentials? = null
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var isManuallyStopped = false

    // Diagnostic information (Section 19)
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _retryCount = MutableStateFlow(0)
    val retryCount: StateFlow<Int> = _retryCount.asStateFlow()

    private val _lastConnectAttempt = MutableStateFlow("-")
    val lastConnectAttempt: StateFlow<String> = _lastConnectAttempt.asStateFlow()

    private val _lastSuccessfulConnect = MutableStateFlow("-")
    val lastSuccessfulConnect: StateFlow<String> = _lastSuccessfulConnect.asStateFlow()

    private val _lastDisconnectReason = MutableStateFlow("-")
    val lastDisconnectReason: StateFlow<String> = _lastDisconnectReason.asStateFlow()

    private val _lastAuthResult = MutableStateFlow("-")
    val lastAuthResult: StateFlow<String> = _lastAuthResult.asStateFlow()

    private val _hubUrl = MutableStateFlow("-")
    val hubUrl: StateFlow<String> = _hubUrl.asStateFlow()

    private val _serverUrl = MutableStateFlow("-")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    // Incoming events
    private val _customerContextEvents = MutableSharedFlow<CallerCustomerContext>(extraBufferCapacity = 64)
    val customerContextEvents: SharedFlow<CallerCustomerContext> = _customerContextEvents.asSharedFlow()

    // Reconnect scheduler (Section 8)
    private var reconnectJob: Job? = null
    private var retryIndex = 0
    private val backoffDelaysMs = listOf(0L, 2000L, 5000L, 10000L, 30000L, 60000L)

    /**
     * Start connection to POS Hub using saved trust credentials.
     * Thread-safe; prevents parallel duplicate connections (Section 9).
     */
    fun start(trust: TrustCredentials) {
        scope.launch {
            connectionMutex.withLock {
                isManuallyStopped = false
                activeTrust = trust
                _serverUrl.value = trust.serverUrl
                _hubUrl.value = PosHubUrlBuilder.buildPosHubUrl(trust.serverUrl)
                retryIndex = 0
                _retryCount.value = 0
                _lastError.value = null
                connectSingleFlight(trust)
            }
        }
    }

    /**
     * Internal connection runner executing negotiation, WebSocket connect,
     * and strictly sequential state transitions.
     */
    private suspend fun connectSingleFlight(trust: TrustCredentials) {
        if (isManuallyStopped) return

        // If currently in progress for same generation, avoid redundant work
        if (_connectionState.value == PosHubState.READY || _connectionState.value == PosHubState.AUTHENTICATING) {
            return
        }

        val gen = currentGeneration.incrementAndGet()
        _connectionState.value = PosHubState.CONNECTING
        _lastConnectAttempt.value = formatTimestamp(Date())

        val fullHubUrl = PosHubUrlBuilder.buildPosHubUrl(trust.serverUrl)
        logInfo("POSHUB_CONNECT_START (generation=$gen)")
        logInfo("POSHUB_URL=$fullHubUrl")

        closeCurrentSocket()

        // Step 1: SignalR Transport Negotiation (Section 15)
        var connectionToken: String? = null
        try {
            val negotiateUrl = PosHubUrlBuilder.buildNegotiateUrl(trust.serverUrl)
            val negRequest = Request.Builder()
                .url(negotiateUrl)
                .header("User-Agent", "ALAMER-Caller-Assistant/1.0")
                .header("Accept", "application/json")
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            okHttpClient.newCall(negRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    connectionToken = json.optString(
                        "connectionToken",
                        json.optString("connectionId", null)
                    )
                    logInfo("POSHUB_NEGOTIATE_SUCCESS (token=${connectionToken?.take(8)}...)")
                } else {
                    logInfo("POSHUB_NEGOTIATE_STATUS: ${response.code}, falling back to direct WebSocket")
                }
            }
        } catch (e: Exception) {
            logInfo("POSHUB_NEGOTIATE_FALLBACK: ${e.message}, proceeding with direct WebSocket")
        }

        // Step 2: Establish OkHttp WebSocket connection
        val wsUrl = PosHubUrlBuilder.buildWebSocketUrl(trust.serverUrl, connectionToken)
        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "ALAMER-Caller-Assistant/1.0")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (currentGeneration.get() != gen) return
                logInfo("POSHUB_SOCKET_OPEN (handshaking protocol 1.0)")
                // Step 3: Send SignalR Protocol Handshake Frame
                val handshakeJson = "{\"protocol\":\"json\",\"version\":1}$RECORD_SEPARATOR"
                ws.send(handshakeJson)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (currentGeneration.get() != gen) return
                handleIncomingFrames(ws, text, gen, trust)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (currentGeneration.get() != gen) return
                logInfo("POSHUB_CLOSED (code=$code, reason=$reason)")
                _lastDisconnectReason.value = "إغلاق الاتصال ($code): ${reason.ifBlank { "طبيعي" }}"
                if (isManuallyStopped) {
                    _connectionState.value = PosHubState.DISCONNECTED
                } else {
                    _connectionState.value = PosHubState.RECONNECTING
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (currentGeneration.get() != gen) return
                val errMsg = t.message ?: "فشل اتصال WebSocket"
                logError("POSHUB_CONNECT_FAILED (msg=$errMsg)", t)
                _lastDisconnectReason.value = errMsg
                _lastError.value = errMsg
                if (isManuallyStopped) {
                    _connectionState.value = PosHubState.DISCONNECTED
                } else {
                    _connectionState.value = PosHubState.RECONNECTING
                    scheduleReconnect()
                }
            }
        })
    }

    /**
     * Handles incoming SignalR messages and executes authentication ONLY after CONNECTED (Section 7 & 11).
     */
    private fun handleIncomingFrames(
        ws: WebSocket,
        text: String,
        generation: Int,
        trust: TrustCredentials
    ) {
        val messages = text.split(RECORD_SEPARATOR).filter { it.isNotBlank() }
        for (msg in messages) {
            try {
                val json = JSONObject(msg)

                // Handshake Response Handling
                if (_connectionState.value == PosHubState.CONNECTING) {
                    if (json.has("error")) {
                        val err = json.optString("error")
                        logError("POSHUB_HANDSHAKE_ERROR: $err")
                        _lastError.value = "فشل مصافحة SignalR: $err"
                        _connectionState.value = PosHubState.FAILED
                        scheduleReconnect()
                        return
                    }

                    // Handshake completed successfully -> state = CONNECTED (Section 3 & 7)
                    _connectionState.value = PosHubState.CONNECTED
                    _lastSuccessfulConnect.value = formatTimestamp(Date())
                    logInfo("POSHUB_CONNECT_SUCCESS")

                    // Now begin authentication step
                    _connectionState.value = PosHubState.AUTHENTICATING
                    logInfo("POSHUB_AUTH_START")
                    sendAuthentication(ws, trust)
                    return
                }

                val type = json.optInt("type", -1)
                when (type) {
                    1 -> { // Server Invocations (e.g. CallerCustomerContext)
                        val target = json.optString("target", "")
                        val args = json.optJSONArray("arguments")
                        handleServerInvocation(target, args)
                    }
                    3 -> { // Completion (Response to AuthenticateCallerAssistant)
                        val invocationId = json.optString("invocationId", "")
                        if (invocationId == "auth-1") {
                            handleAuthCompletion(json)
                        }
                    }
                    6 -> { // Ping
                        ws.send("{\"type\":6}$RECORD_SEPARATOR")
                    }
                }
            } catch (e: Exception) {
                logError("POSHUB_FRAME_PARSE_ERROR: ${e.message}", e)
            }
        }
    }

    /**
     * Invokes AuthenticateCallerAssistant with exact contract (Section 16).
     */
    private fun sendAuthentication(ws: WebSocket, trust: TrustCredentials) {
        val authReq = SignalRAuthRequest(
            deviceId = trust.deviceId,
            deviceName = trust.deviceName,
            installationBinding = trust.installationBinding,
            callerCredential = trust.callerCredential,
            protocolVersion = trust.protocolVersion,
            platform = "Android"
        )
        val authJson = authAdapter.toJson(authReq)

        val invocation = JSONObject().apply {
            put("type", 1)
            put("invocationId", "auth-1")
            put("target", "AuthenticateCallerAssistant")
            put("arguments", JSONArray().put(JSONObject(authJson)))
        }

        val credRedacted = if (trust.callerCredential.length > 4) {
            "***" + trust.callerCredential.takeLast(4)
        } else "[SET]"
        logInfo("POSHUB_SEND_AUTH (deviceId=${trust.deviceId}, cred=$credRedacted)")
        ws.send(invocation.toString() + RECORD_SEPARATOR)
    }

    /**
     * Parses and validates AuthenticateCallerAssistant response (Section 2, 17, 18).
     */
    private fun handleAuthCompletion(json: JSONObject) {
        val error = json.optString("error", "")
        if (error.isNotBlank()) {
            logError("POSHUB_AUTH_FAILED: $error")
            _lastAuthResult.value = "فشل: $error"
            _lastError.value = "فشل المصادقة: $error"

            if (error.contains("Unauthorized", ignoreCase = true) ||
                error.contains("Credential", ignoreCase = true) ||
                error.contains("Revoked", ignoreCase = true)
            ) {
                // Do not retry blindly on invalid credentials (Section 28)
                _connectionState.value = PosHubState.FAILED
                return
            }

            _connectionState.value = PosHubState.FAILED
            scheduleReconnect()
            return
        }

        // Result parsing
        val rawResult = json.opt("result")
        if (rawResult == null) {
            logInfo("POSHUB_AUTH_SUCCESS (empty result, accepted)")
            _lastAuthResult.value = "معتمد ✓"
            _connectionState.value = PosHubState.READY
            logInfo("POSHUB_READY")
            resetRetryPolicy()
            return
        }

        try {
            val pairingResult = PairingResultParser.parse(rawResult.toString())
            val isApproved = pairingResult.success ||
                    pairingResult.deviceStatus.equals("Approved", ignoreCase = true)

            if (isApproved) {
                logInfo("POSHUB_AUTH_SUCCESS: capabilities=${pairingResult.capabilities}, status=${pairingResult.deviceStatus}")
                _lastAuthResult.value = "معتمد (${pairingResult.deviceStatus ?: "Approved"}) ✓"
                _connectionState.value = PosHubState.READY
                logInfo("POSHUB_READY")
                resetRetryPolicy()
            } else {
                val msg = pairingResult.message ?: pairingResult.deviceStatus ?: "تم رفض الاعتماد"
                logError("POSHUB_AUTH_REJECTED: $msg")
                _lastAuthResult.value = "مرفوض: $msg"
                _lastError.value = msg
                _connectionState.value = PosHubState.FAILED
                scheduleReconnect()
            }
        } catch (e: Exception) {
            logError("POSHUB_AUTH_PARSE_FAILED: ${e.message}", e)
            _lastAuthResult.value = "فشل قراءة الرد: ${e.message}"
            _connectionState.value = PosHubState.FAILED
            scheduleReconnect()
        }
    }

    private fun handleServerInvocation(target: String, args: JSONArray?) {
        if (target.equals("CallerCustomerContext", ignoreCase = true) ||
            target.equals("OnCallerCustomerContext", ignoreCase = true) ||
            target.equals("CallerContextReceived", ignoreCase = true)
        ) {
            if (args != null && args.length() > 0) {
                val argObj = args.optJSONObject(0)
                if (argObj != null) {
                    val parsed = customerContextAdapter.fromJson(argObj.toString())
                    if (parsed != null) {
                        scope.launch {
                            _customerContextEvents.emit(parsed)
                        }
                    }
                }
            }
        }
    }

    /**
     * Invokes RecordCallerCall on SignalR.
     */
    fun recordCallerCall(record: CallRecordRequest): Boolean {
        val ws = webSocket ?: return false
        if (_connectionState.value != PosHubState.READY) return false

        return try {
            val recordJson = callRecordAdapter.toJson(record)
            val invId = "call-" + invocationCounter.getAndIncrement()
            val invocation = JSONObject().apply {
                put("type", 1)
                put("invocationId", invId)
                put("target", "RecordCallerCall")
                put("arguments", JSONArray().put(JSONObject(recordJson)))
            }
            ws.send(invocation.toString() + RECORD_SEPARATOR)
        } catch (e: Exception) {
            logError("POSHUB_RECORD_CALL_ERROR: ${e.message}", e)
            false
        }
    }

    /**
     * Automatic reconnect backoff policy (Section 8).
     * Delays: 0s, 2s, 5s, 10s, 30s, 60s.
     */
    private fun scheduleReconnect() {
        if (isManuallyStopped) return
        val trust = activeTrust ?: return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = backoffDelaysMs.getOrElse(retryIndex) { 60000L }
            if (retryIndex < backoffDelaysMs.size - 1) {
                retryIndex++
            }
            _retryCount.value++
            val currentAttempt = _retryCount.value
            logInfo("POSHUB_RECONNECTING (attempt=$currentAttempt, delay=${delayMs}ms)")

            if (delayMs > 0) {
                delay(delayMs)
            }

            connectionMutex.withLock {
                if (!isManuallyStopped && _connectionState.value != PosHubState.READY) {
                    logInfo("POSHUB_RECONNECTED (initiating handshake attempt=$currentAttempt)")
                    connectSingleFlight(trust)
                }
            }
        }
    }

    /**
     * Called when network connection returns (Section 20).
     * Triggers immediate reconnection without redundant delay.
     */
    fun onNetworkAvailable() {
        val trust = activeTrust ?: return
        if (isManuallyStopped) return

        if (_connectionState.value == PosHubState.DISCONNECTED ||
            _connectionState.value == PosHubState.RECONNECTING ||
            _connectionState.value == PosHubState.FAILED
        ) {
            scope.launch {
                connectionMutex.withLock {
                    logInfo("POSHUB_NETWORK_RESTORED: Triggering immediate reconnect")
                    retryIndex = 0
                    connectSingleFlight(trust)
                }
            }
        }
    }

    private fun resetRetryPolicy() {
        retryIndex = 0
        _retryCount.value = 0
        reconnectJob?.cancel()
    }

    /**
     * Stops and closes connection.
     */
    fun stop() {
        isManuallyStopped = true
        reconnectJob?.cancel()
        _connectionState.value = PosHubState.DISCONNECTED
        closeCurrentSocket()
        logInfo("POSHUB_STOPPED")
    }

    private fun closeCurrentSocket() {
        try {
            webSocket?.close(1000, "App paused or re-initializing")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
    }

    private fun formatTimestamp(date: Date): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
    }

    private fun logInfo(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
            println("$TAG: $msg")
        }
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        try {
            android.util.Log.e(TAG, msg, tr)
        } catch (_: Throwable) {
            System.err.println("$TAG: $msg")
            tr?.printStackTrace()
        }
    }
}
