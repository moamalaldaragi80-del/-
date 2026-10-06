package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.example.model.ConnectionState
import com.example.model.DiagnosticsReport
import com.example.model.PairRequest
import com.example.model.QrPairingData
import com.example.model.ReconnectRequest
import com.example.model.SignalRAuthRequest
import com.example.model.TrustCredentials
import com.example.network.DiscoveredServer
import com.example.network.QrParserAndValidator
import com.example.network.SignalRClient
import com.example.network.SignalRConnectionState
import com.example.network.TaloolaHttpClient
import com.example.network.UdpDiscoveryClient
import com.example.security.SecureStorageManager
import com.example.telephony.CallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlamerRepository(
    private val context: Context,
    val secureStorage: SecureStorageManager,
    val httpClient: TaloolaHttpClient,
    val signalRClient: SignalRClient,
    val udpDiscoveryClient: UdpDiscoveryClient,
    val callManager: CallManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.UNINITIALIZED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trustCredentials = MutableStateFlow<TrustCredentials?>(null)
    val trustCredentials: StateFlow<TrustCredentials?> = _trustCredentials.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _diagnosticsReport = MutableStateFlow(DiagnosticsReport())
    val diagnosticsReport: StateFlow<DiagnosticsReport> = _diagnosticsReport.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow<String>("-")
    val lastSyncTimestamp: StateFlow<String> = _lastSyncTimestamp.asStateFlow()

    private var connectionJob: Job? = null

    init {
        // Observe SignalR state changes
        scope.launch {
            signalRClient.connectionState.collect { sigState ->
                when (sigState) {
                    SignalRConnectionState.CONNECTED -> {
                        _connectionState.value = ConnectionState.READY
                        _lastSyncTimestamp.value = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        callManager.onSignalRConnected()
                    }
                    SignalRConnectionState.AUTHENTICATING -> {
                        _connectionState.value = ConnectionState.AUTHENTICATING
                    }
                    SignalRConnectionState.CONNECTING, SignalRConnectionState.HANDSHAKING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    SignalRConnectionState.FAILED -> {
                        val err = signalRClient.lastError.value
                        if (err != null && err.contains("CREDENTIAL", ignoreCase = true)) {
                            _connectionState.value = ConnectionState.CREDENTIAL_INVALID
                        } else if (_connectionState.value == ConnectionState.READY || _connectionState.value == ConnectionState.AUTHENTICATING) {
                            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
                        }
                    }
                    SignalRConnectionState.DISCONNECTED -> {
                        if (_connectionState.value == ConnectionState.READY) {
                            _connectionState.value = ConnectionState.CONNECTING
                        }
                    }
                }
            }
        }
    }

    /**
     * Called at application startup
     */
    fun initialize() {
        val savedTrust = secureStorage.loadTrust()
        _trustCredentials.value = savedTrust
        if (savedTrust == null) {
            _connectionState.value = ConnectionState.NEEDS_PAIRING
        } else {
            // Trust intact -> RECONNECTING automatically (Rule 12)
            _connectionState.value = ConnectionState.CONNECTING
            startAutoReconnect(savedTrust)
        }
    }

    /**
     * 7, 8, 9. Execute QR Validation & Pairing flow
     */
    suspend fun pairWithQr(qrData: QrPairingData): Result<TrustCredentials> {
        _connectionState.value = ConnectionState.PAIRING
        _lastError.value = null

        // 8. GET /api/server/info
        val infoResult = httpClient.getServerInfo(qrData.host, qrData.port, qrData.tls)
        if (infoResult.isFailure) {
            val err = "تعذر الاتصال بالخادم على ${qrData.host}:${qrData.port}"
            _lastError.value = err
            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
            return Result.failure(Exception(err))
        }

        val serverInfo = infoResult.getOrThrow()

        // Validate ServerId match (Rule 7: ServerId(response) == ServerId(QR))
        val responseServerId = serverInfo.serverId ?: ""
        if (!responseServerId.equals(qrData.serverId, ignoreCase = true)) {
            val mismatchErr = "الخادم الموجود لا يطابق الخادم المقصود"
            _lastError.value = mismatchErr
            _connectionState.value = ConnectionState.SERVER_ID_MISMATCH
            return Result.failure(Exception(mismatchErr))
        }

        // Protocol check (Rule 8)
        val proto = serverInfo.protocolVersion ?: qrData.protocol
        if (proto != "1.0") {
            val protoErr = "عدم تطابق إصدار البروتوكول: متوقع 1.0 ووجد $proto"
            _lastError.value = protoErr
            _connectionState.value = ConnectionState.PROTOCOL_MISMATCH
            return Result.failure(Exception(protoErr))
        }

        // 9. PAIRING: POST /api/caller-assistant/pair
        val deviceId = secureStorage.getOrCreateDeviceId()
        val deviceName = Build.MODEL
        val instBinding = secureStorage.getOrCreateInstallationBinding()

        val pairRequest = PairRequest(
            version = qrData.version.toIntOrNull() ?: 1,
            deviceType = "CallerAssistant",
            serverId = qrData.serverId,
            protocolVersion = qrData.protocol,
            pairingId = qrData.pairingId,
            token = qrData.token,
            deviceId = deviceId,
            deviceName = deviceName,
            installationBinding = instBinding
        )

        val pairResult = httpClient.pair(qrData.host, qrData.port, qrData.tls, pairRequest)
        if (pairResult.isFailure) {
            val err = pairResult.exceptionOrNull()?.message ?: "فشل طلب الاقتران"
            _lastError.value = err
            _connectionState.value = ConnectionState.NEEDS_REPAIR
            return Result.failure(Exception(err))
        }

        val pairResponse = pairResult.getOrThrow()
        val callerCred = pairResponse.callerCredential
        if (callerCred.isNullOrBlank()) {
            val err = "لم يرجع الخادم بيانات الاعتماد CallerCredential"
            _lastError.value = err
            _connectionState.value = ConnectionState.NEEDS_REPAIR
            return Result.failure(Exception(err))
        }

        // 10, 11. Save Trust to Android Keystore Secure Storage
        val trust = TrustCredentials(
            serverId = qrData.serverId,
            serverName = serverInfo.serverName ?: qrData.name,
            serverUrl = pairResponse.serverUrl ?: "http://${qrData.host}:${qrData.port}",
            host = qrData.host,
            port = qrData.port,
            tlsRequired = qrData.tls,
            protocolVersion = qrData.protocol,
            deviceId = deviceId,
            deviceName = deviceName,
            installationBinding = instBinding,
            callerCredential = callerCred,
            hubPath = "/posHub",
            pairedAtEpochMs = System.currentTimeMillis()
        )

        secureStorage.saveTrust(trust)
        _trustCredentials.value = trust
        _connectionState.value = ConnectionState.PAIRING_SUCCESS

        // Immediately start SignalR connection
        startSignalR(trust)
        return Result.success(trust)
    }

    /**
     * 13. Reconnect flow with DHCP discovery fallback (Rule 15)
     */
    fun startAutoReconnect(trust: TrustCredentials) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            _lastError.value = null

            // First attempt saved endpoint
            var activeHost = trust.host
            var activePort = trust.port
            var activeUrl = trust.serverUrl

            val reconnectReq = ReconnectRequest(
                deviceId = trust.deviceId,
                installationBinding = trust.installationBinding,
                serverId = trust.serverId,
                callerCredential = trust.callerCredential,
                protocolVersion = trust.protocolVersion
            )

            var reconnectResult = httpClient.reconnect(activeHost, activePort, trust.tlsRequired, reconnectReq)

            // If saved endpoint failed due to network / host unreachable, attempt DHCP Recovery (Rule 15)
            if (reconnectResult.isFailure && reconnectResult.exceptionOrNull() !is SecurityException) {
                val discovered = udpDiscoveryClient.discoverServer(trust.serverId, timeoutMs = 2500)
                    ?: udpDiscoveryClient.probeSubnetForServer(getLocalIpAddress(), trust.serverId)

                if (discovered != null) {
                    activeHost = discovered.host
                    activePort = discovered.port
                    activeUrl = discovered.serverUrl
                    secureStorage.updateEndpoint(activeHost, activePort, activeUrl)

                    // Retry reconnect with new endpoint
                    reconnectResult = httpClient.reconnect(activeHost, activePort, trust.tlsRequired, reconnectReq)
                } else {
                    _lastError.value = "الجهاز الرئيسي غير متاح على الشبكة الحالية"
                    _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
                    return@launch
                }
            }

            if (reconnectResult.isFailure) {
                val ex = reconnectResult.exceptionOrNull()
                if (ex is SecurityException) {
                    if (ex.message == "DEVICE_REVOKED") {
                        _connectionState.value = ConnectionState.DEVICE_REVOKED
                        _lastError.value = "تم إلغاء ترخيص هذا الجهاز من قبل المشرف"
                    } else {
                        _connectionState.value = ConnectionState.CREDENTIAL_INVALID
                        _lastError.value = "بيانات الاعتماد غير صالحة، يرجى إعادة الاقتران"
                    }
                } else {
                    _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
                    _lastError.value = ex?.message ?: "تعذر الوصول إلى الخادم"
                }
                return@launch
            }

            // Success -> Connect SignalR
            val updatedTrust = trust.copy(host = activeHost, port = activePort, serverUrl = activeUrl)
            _trustCredentials.value = updatedTrust
            startSignalR(updatedTrust)
        }
    }

    private fun startSignalR(trust: TrustCredentials) {
        val authReq = SignalRAuthRequest(
            deviceId = trust.deviceId,
            deviceName = trust.deviceName,
            installationBinding = trust.installationBinding,
            callerCredential = trust.callerCredential,
            protocolVersion = trust.protocolVersion,
            platform = "Android"
        )

        signalRClient.start(
            host = trust.host,
            port = trust.port,
            tls = trust.tlsRequired,
            hubPath = trust.hubPath,
            authRequest = authReq
        )
    }

    /**
     * 34. RESET / Unpair
     */
    fun unpair() {
        signalRClient.stop()
        secureStorage.clearTrust()
        _trustCredentials.value = null
        _connectionState.value = ConnectionState.NEEDS_PAIRING
        _lastError.value = null
        callManager.clearHistory()
    }

    /**
     * 32. DIAGNOSTICS: Run comprehensive diagnostics
     */
    suspend fun runDiagnostics(): DiagnosticsReport {
        val trust = _trustCredentials.value
        val localIp = getLocalIpAddress()
        val wifiSsid = getWifiSsid()
        val isWifi = isWifiConnected()

        var reachability = false
        var infoStatus = "-"
        var serverIdMatch = false

        if (trust != null) {
            val healthRes = httpClient.checkHealth(trust.host, trust.port, trust.tlsRequired)
            reachability = healthRes.isSuccess

            val infoRes = httpClient.getServerInfo(trust.host, trust.port, trust.tlsRequired)
            if (infoRes.isSuccess) {
                val info = infoRes.getOrNull()
                infoStatus = "HTTP 200 (OK)"
                serverIdMatch = info?.serverId.equals(trust.serverId, ignoreCase = true)
            } else {
                infoStatus = infoRes.exceptionOrNull()?.message ?: "فشل الاتصال"
            }
        }

        val report = DiagnosticsReport(
            wifiConnected = isWifi,
            wifiSsid = wifiSsid,
            localIp = localIp,
            savedHost = trust?.host ?: "-",
            savedPort = trust?.port ?: 0,
            serverReachability = reachability,
            serverInfoStatus = infoStatus,
            serverIdMatch = serverIdMatch,
            credentialStatus = if (trust != null) "محفوظة وموثوقة" else "غير مقترن",
            signalRStatus = signalRClient.connectionState.value.name,
            lastError = _lastError.value ?: signalRClient.lastError.value,
            retryCount = signalRClient.retryCount.value,
            lastSyncTime = _lastSyncTimestamp.value
        )

        _diagnosticsReport.value = report
        return report
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun getWifiSsid(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val ssid = info?.ssid?.replace("\"", "")
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "شبكة LAN متصلة" else ssid
        } catch (e: Exception) {
            "شبكة محلية"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return "127.0.0.1"
    }
}
