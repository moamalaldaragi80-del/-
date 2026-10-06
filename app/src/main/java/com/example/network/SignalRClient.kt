package com.example.network

import com.example.model.CallRecordRequest
import com.example.model.CallerCustomerContext
import com.example.model.SignalRAuthRequest
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class SignalRConnectionState {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKING,
    AUTHENTICATING,
    CONNECTED,
    FAILED
}

/**
 * SignalR Java client over OkHttp WebSocket implementing the ASP.NET Core SignalR JSON Hub protocol.
 * Hub Path: /posHub
 */
class SignalRClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive for WebSockets
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val authAdapter = moshi.adapter(SignalRAuthRequest::class.java)
    private val callRecordAdapter = moshi.adapter(CallRecordRequest::class.java)
    private val customerContextAdapter = moshi.adapter(CallerCustomerContext::class.java)

    private val recordSeparator = "\u001e"
    private val invocationCounter = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    private var activeHost: String = ""
    private var activePort: Int = 5090
    private var activeTls: Boolean = false
    private var activeAuthRequest: SignalRAuthRequest? = null

    private var retryIndex = 0
    private val backoffDelaysMs = listOf(1000L, 2000L, 4000L, 8000L, 15000L, 30000L)

    private val _connectionState = MutableStateFlow(SignalRConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalRConnectionState> = _connectionState.asStateFlow()

    private val _customerContextEvents = MutableSharedFlow<CallerCustomerContext>(extraBufferCapacity = 64)
    val customerContextEvents: SharedFlow<CallerCustomerContext> = _customerContextEvents.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _retryCount = MutableStateFlow(0)
    val retryCount: StateFlow<Int> = _retryCount.asStateFlow()

    private var isManuallyStopped = false

    fun start(
        host: String,
        port: Int,
        tls: Boolean,
        hubPath: String = "/posHub",
        authRequest: SignalRAuthRequest
    ) {
        isManuallyStopped = false
        activeHost = host
        activePort = port
        activeTls = tls
        activeAuthRequest = authRequest
        retryIndex = 0
        _retryCount.value = 0
        connectInternal(hubPath)
    }

    private fun connectInternal(hubPath: String = "/posHub") {
        if (isManuallyStopped) return
        closeSocket()

        val scheme = if (activeTls) "wss" else "ws"
        val cleanPath = if (hubPath.startsWith("/")) hubPath else "/$hubPath"
        val wsUrl = "$scheme://$activeHost:$activePort$cleanPath"

        _connectionState.value = SignalRConnectionState.CONNECTING
        _lastError.value = null

        val request = Request.Builder()
            .url(wsUrl)
            .header("User-Agent", "ALAMER-Caller-Assistant/1.0")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = SignalRConnectionState.HANDSHAKING
                // Send SignalR Handshake
                val handshakeJson = "{\"protocol\":\"json\",\"version\":1}$recordSeparator"
                webSocket.send(handshakeJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingFrames(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = SignalRConnectionState.DISCONNECTED
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = SignalRConnectionState.FAILED
                _lastError.value = t.message ?: "فشل اتصال WebSocket"
                scheduleReconnect()
            }
        })
    }

    private fun handleIncomingFrames(ws: WebSocket, text: String) {
        val messages = text.split(recordSeparator).filter { it.isNotBlank() }
        for (msg in messages) {
            try {
                val json = JSONObject(msg)
                // Handshake response check: {}
                if (_connectionState.value == SignalRConnectionState.HANDSHAKING) {
                    if (json.has("error")) {
                        val err = json.optString("error")
                        _lastError.value = "فشل مصافحة SignalR: $err"
                        _connectionState.value = SignalRConnectionState.FAILED
                        return
                    }
                    // Handshake completed! Now authenticate
                    _connectionState.value = SignalRConnectionState.AUTHENTICATING
                    sendAuthentication(ws)
                    return
                }

                val type = json.optInt("type", -1)
                when (type) {
                    1 -> { // Invocation
                        val target = json.optString("target", "")
                        val args = json.optJSONArray("arguments")
                        handleServerInvocation(target, args)
                    }
                    3 -> { // Completion (Response to invocation)
                        val invocationId = json.optString("invocationId", "")
                        if (invocationId == "auth-1") {
                            val error = json.optString("error", "")
                            if (error.isNotBlank()) {
                                _lastError.value = "فشل المصادقة: $error"
                                _connectionState.value = SignalRConnectionState.FAILED
                            } else {
                                // Authenticated successfully!
                                _connectionState.value = SignalRConnectionState.CONNECTED
                                retryIndex = 0
                                _retryCount.value = 0
                            }
                        }
                    }
                    6 -> { // Ping
                        ws.send("{\"type\":6}$recordSeparator")
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors on ping / frame
            }
        }
    }

    private fun sendAuthentication(ws: WebSocket) {
        val auth = activeAuthRequest ?: return
        val authJson = authAdapter.toJson(auth)

        val invocation = JSONObject().apply {
            put("type", 1)
            put("invocationId", "auth-1")
            put("target", "AuthenticateCallerAssistant")
            put("arguments", JSONArray().put(JSONObject(authJson)))
        }

        ws.send(invocation.toString() + recordSeparator)
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
     * 18. RECORD CALL: Invokes RecordCallerCall on SignalR
     */
    fun recordCallerCall(record: CallRecordRequest): Boolean {
        val ws = webSocket ?: return false
        if (_connectionState.value != SignalRConnectionState.CONNECTED) return false

        return try {
            val recordJson = callRecordAdapter.toJson(record)
            val invId = "call-" + invocationCounter.getAndIncrement()
            val invocation = JSONObject().apply {
                put("type", 1)
                put("invocationId", invId)
                put("target", "RecordCallerCall")
                put("arguments", JSONArray().put(JSONObject(recordJson)))
            }
            ws.send(invocation.toString() + recordSeparator)
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleReconnect() {
        if (isManuallyStopped) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = backoffDelaysMs.getOrElse(retryIndex) { 30000L }
            if (retryIndex < backoffDelaysMs.size - 1) {
                retryIndex++
            }
            _retryCount.value++
            delay(delayMs)
            connectInternal()
        }
    }

    fun stop() {
        isManuallyStopped = true
        reconnectJob?.cancel()
        closeSocket()
        _connectionState.value = SignalRConnectionState.DISCONNECTED
    }

    private fun closeSocket() {
        try {
            webSocket?.close(1000, "App paused")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
    }
}
