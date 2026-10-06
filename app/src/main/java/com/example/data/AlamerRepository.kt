package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import com.example.model.AlamerErrors
import com.example.model.ConnectionState
import com.example.model.DiagnosticsReport
import com.example.model.PairRequest
import com.example.model.QrPairingData
import com.example.model.ReconnectRequest
import com.example.model.ServerInfoResponse
import com.example.model.ServerMismatchDetails
import com.example.model.SignalRAuthRequest
import com.example.model.TrustCredentials
import com.example.model.UntrustedServerPrompt
import com.example.network.DiscoveredServer
import com.example.network.PosHubConnectionManager
import com.example.network.SignalRClient
import com.example.network.SignalRConnectionState
import com.example.network.TaloolaHttpClient
import com.example.network.UdpDiscoveryClient
import com.example.network.UrlNormalizer
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

    private val _serverMismatchDetails = MutableStateFlow<ServerMismatchDetails?>(null)
    val serverMismatchDetails: StateFlow<ServerMismatchDetails?> = _serverMismatchDetails.asStateFlow()

    private val _untrustedServerPrompt = MutableStateFlow<UntrustedServerPrompt?>(null)
    val untrustedServerPrompt: StateFlow<UntrustedServerPrompt?> = _untrustedServerPrompt.asStateFlow()

    private var connectionJob: Job? = null

    val posHubManager: PosHubConnectionManager = signalRClient.hubManager

    init {
        // Observe POS Hub state changes (Section 5 & 6)
        scope.launch {
            posHubManager.connectionState.collect { posState ->
                when (posState) {
                    com.example.model.PosHubState.READY -> {
                        _connectionState.value = ConnectionState.READY
                        _lastSyncTimestamp.value = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        callManager.onSignalRConnected()
                    }
                    com.example.model.PosHubState.AUTHENTICATING -> {
                        _connectionState.value = ConnectionState.AUTHENTICATING
                    }
                    com.example.model.PosHubState.CONNECTING,
                    com.example.model.PosHubState.CONNECTED,
                    com.example.model.PosHubState.RECONNECTING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    com.example.model.PosHubState.FAILED -> {
                        val err = posHubManager.lastError.value
                        if (err != null && err.contains("CREDENTIAL", ignoreCase = true)) {
                            _connectionState.value = ConnectionState.CREDENTIAL_INVALID
                            _lastError.value = AlamerErrors.formatCredentialInvalid()
                        } else if (_connectionState.value == ConnectionState.READY || _connectionState.value == ConnectionState.AUTHENTICATING) {
                            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
                        }
                    }
                    com.example.model.PosHubState.DISCONNECTED -> {
                        if (_trustCredentials.value == null) {
                            _connectionState.value = ConnectionState.NEEDS_PAIRING
                        } else if (_connectionState.value == ConnectionState.READY) {
                            _connectionState.value = ConnectionState.CONNECTING
                        }
                    }
                    com.example.model.PosHubState.STOPPING -> {
                        // Shutdown in flight
                    }
                }
            }
        }

        // Automatic network reconnection callback (Section 20)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    posHubManager.onNetworkAvailable()
                }
            })
        } catch (_: Exception) {
            // Ignore in environments without connectivity service
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
            // Trust intact -> RECONNECTING automatically (Rule 14)
            _connectionState.value = ConnectionState.CONNECTING
            startAutoReconnect(savedTrust)
        }
    }

    /**
     * V103 DIRECT LAN AUTO-PAIR:
     * Connect by Manual URL (Mode B / Default Flow)
     * - Normalize URL (e.g. 192.168.68.104:5000 -> http://192.168.68.104:5000)
     * - GET /api/server/info
     * - CallerAssistantEnabled == true & DIRECT_LAN_AUTO_PAIR mode
     * - Never reject first connection due to an old SavedServerId!
     * - If already paired with this server: RECONNECT directly
     * - If first connection or new server: POST /api/caller-assistant/auto-pair
     * - Save to encrypted storage & Authenticate via SignalR -> READY
     */
    suspend fun connectByUrl(rawUrl: String): Result<ServerInfoResponse> {
        _lastError.value = null
        _untrustedServerPrompt.value = null
        _serverMismatchDetails.value = null

        val normalized = UrlNormalizer.normalize(rawUrl)
        if (normalized == null) {
            val err = "صيغة الرابط غير صالحة. يرجى إدخال عنوان خادم صالح مثل http://192.168.68.104:5000"
            _lastError.value = err
            return Result.failure(IllegalArgumentException(err))
        }

        _connectionState.value = ConnectionState.CONNECTING

        // Step 1: GET /api/server/info
        val infoResult = httpClient.getServerInfoByUrl(normalized.fullUrl)
        if (infoResult.isFailure) {
            val err = AlamerErrors.formatServerUnreachable(normalized.fullUrl)
            _lastError.value = err
            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
            return Result.failure(Exception(err))
        }

        val serverInfo = infoResult.getOrThrow()

        // Step 2: Check CallerAssistantEnabled
        if (serverInfo.callerAssistantEnabled == false) {
            val err = "خدمة البدالة (Caller Assistant) غير مفعلة على جهاز Taloola الرئيسي."
            _lastError.value = err
            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
            return Result.failure(Exception(err))
        }

        val serverId = serverInfo.serverId ?: ""
        val savedTrust = secureStorage.loadTrust()

        // Step 3: If already paired with this exact server, reuse saved credentials
        if (savedTrust != null && savedTrust.serverId.isNotBlank() && savedTrust.serverId.equals(serverId, ignoreCase = true)) {
            if (savedTrust.host != normalized.host || savedTrust.port != normalized.port) {
                secureStorage.updateEndpoint(normalized.host, normalized.port, normalized.fullUrl)
            }
            val updatedTrust = savedTrust.copy(
                host = normalized.host,
                port = normalized.port,
                serverUrl = normalized.fullUrl,
                serverName = serverInfo.displayName
            )
            _trustCredentials.value = updatedTrust
            startAutoReconnect(updatedTrust)
            return Result.success(serverInfo)
        }

        // Step 4: First connection or reinstall -> Direct LAN Auto-Pair!
        // In first connection, never use saved identity to reject.
        val autoPairResult = executeAutoPair(
            serverUrl = normalized.fullUrl,
            serverId = serverId,
            restaurantName = serverInfo.displayName
        )

        return if (autoPairResult.isSuccess) {
            Result.success(serverInfo)
        } else {
            Result.failure(autoPairResult.exceptionOrNull() ?: Exception("فشل الإعداد التلقائي"))
        }
    }

    /**
     * V103 Execute Direct LAN Auto-Pair
     */
    suspend fun executeAutoPair(
        serverUrl: String,
        serverId: String? = null,
        restaurantName: String? = null
    ): Result<TrustCredentials> {
        val normalized = UrlNormalizer.normalize(serverUrl)
            ?: return Result.failure(Exception("رابط الخادم غير صالح"))

        val deviceId = secureStorage.getOrCreateDeviceId()
        val instBinding = secureStorage.getOrCreateInstallationBinding()
        val deviceName = "Alamer بدالة"

        val autoPairReq = com.example.model.AutoPairRequest(
            version = 1,
            deviceType = "CallerAssistant",
            deviceId = deviceId,
            deviceName = deviceName,
            installationBinding = instBinding,
            protocolVersion = "1.0"
        )

        _connectionState.value = ConnectionState.PAIRING
        val pairRes = httpClient.autoPair(normalized.fullUrl, autoPairReq)
        if (pairRes.isFailure) {
            val err = pairRes.exceptionOrNull()?.message ?: AlamerErrors.formatServerUnreachable(normalized.fullUrl)
            _lastError.value = err
            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
            return Result.failure(Exception(err))
        }

        val response = pairRes.getOrThrow()
        val callerCred = response.callerCredential
        if (callerCred.isNullOrBlank()) {
            val err = "لم يرجع الخادم بيانات الاعتماد CallerCredential"
            _lastError.value = err
            _connectionState.value = ConnectionState.NEEDS_PAIRING
            return Result.failure(Exception(err))
        }

        val finalServerId = response.serverId ?: serverId ?: ""
        val finalServerName = restaurantName ?: response.deviceName ?: "تعلولة"

        val trust = TrustCredentials(
            serverId = finalServerId,
            serverName = finalServerName,
            serverUrl = response.serverUrl ?: normalized.fullUrl,
            host = normalized.host,
            port = normalized.port,
            tlsRequired = normalized.tls,
            protocolVersion = response.protocolVersion ?: "1.0",
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
        _untrustedServerPrompt.value = null
        _serverMismatchDetails.value = null

        startSignalR(trust)
        return Result.success(trust)
    }

    private val _verifiedQrSession = MutableStateFlow<com.example.model.VerifiedQrSession?>(null)
    val verifiedQrSession: StateFlow<com.example.model.VerifiedQrSession?> = _verifiedQrSession.asStateFlow()

    /**
     * V101 Steps 1 to 5: Verify QR session against live server pair-info endpoint.
     * SavedServerId in Android is NEVER used as a barrier against a fresh QR!
     */
    suspend fun verifyQrSession(qrData: QrPairingData): Result<com.example.model.VerifiedQrSession> {
        _connectionState.value = ConnectionState.PAIRING
        _lastError.value = null
        _serverMismatchDetails.value = null
        _verifiedQrSession.value = null

        // Step 3: GET /api/caller-assistant/pair-info?pid=<QR.pid>
        val pairInfoRes = httpClient.getPairInfo(qrData.host, qrData.port, qrData.tls, qrData.pairingId)
        if (pairInfoRes.isFailure) {
            val err = pairInfoRes.exceptionOrNull()?.message ?: AlamerErrors.formatServerUnreachable("http://${qrData.host}:${qrData.port}")
            _lastError.value = err
            _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
            return Result.failure(Exception(err))
        }

        val pairInfo = pairInfoRes.getOrThrow()

        // Step 4: True live verification
        val liveServerId = pairInfo.serverId ?: ""
        val livePairingId = pairInfo.pairingId ?: ""

        // Check if QR.pid matches live session
        if (livePairingId.isNotBlank() && !livePairingId.equals(qrData.pairingId, ignoreCase = true)) {
            val err = AlamerErrors.formatPairingIdInvalid()
            _lastError.value = err
            _connectionState.value = ConnectionState.NEEDS_REPAIR
            return Result.failure(Exception(err))
        }

        // True SERVER_ID_MISMATCH check:
        // ONLY when the live server at this IP/port returns a ServerId that does NOT match QR.sid!
        if (liveServerId.isNotBlank() && !liveServerId.equals(qrData.serverId, ignoreCase = true)) {
            val mismatchErr = AlamerErrors.formatServerIdMismatch(
                qrSid = qrData.serverId,
                currentSid = liveServerId,
                url = "http://${qrData.host}:${qrData.port}"
            )
            _serverMismatchDetails.value = ServerMismatchDetails(
                restaurantName = pairInfo.displayName,
                serverUrl = "http://${qrData.host}:${qrData.port}",
                currentServerId = liveServerId,
                qrServerId = qrData.serverId,
                pendingQrData = qrData
            )
            _lastError.value = mismatchErr
            _connectionState.value = ConnectionState.SERVER_ID_MISMATCH
            return Result.failure(Exception(mismatchErr))
        }

        // Protocol check
        val proto = pairInfo.protocolVersion ?: qrData.protocol
        if (proto != "1.0") {
            val protoErr = AlamerErrors.formatProtocolMismatch()
            _lastError.value = protoErr
            _connectionState.value = ConnectionState.PROTOCOL_MISMATCH
            return Result.failure(Exception(protoErr))
        }

        // Step 5: Check against local saved ServerId (SavedServerId is NOT a blocker!)
        val savedTrust = secureStorage.loadTrust()
        val isDifferent = savedTrust != null && !savedTrust.serverId.equals(qrData.serverId, ignoreCase = true)

        val session = com.example.model.VerifiedQrSession(
            qrData = qrData,
            restaurantName = pairInfo.displayName,
            serverUrl = "http://${qrData.host}:${qrData.port}",
            serverId = qrData.serverId,
            isDifferentFromSavedServer = isDifferent
        )
        _verifiedQrSession.value = session
        return Result.success(session)
    }

    /**
     * V101 Step 6: Execute pair after user confirmation
     */
    suspend fun executeVerifiedPairing(session: com.example.model.VerifiedQrSession): Result<TrustCredentials> {
        _connectionState.value = ConnectionState.PAIRING
        _lastError.value = null

        val qrData = session.qrData
        // If switching from another server, replace trust upon confirmation
        if (session.isDifferentFromSavedServer) {
            secureStorage.clearTrust()
            _trustCredentials.value = null
        }

        val result = executePairingRequest(
            host = qrData.host,
            port = qrData.port,
            tls = qrData.tls,
            serverId = session.serverId,
            serverName = session.restaurantName,
            pairingId = qrData.pairingId,
            token = qrData.token
        )

        if (result.isSuccess) {
            _verifiedQrSession.value = null
            _serverMismatchDetails.value = null
            _untrustedServerPrompt.value = null
        }
        return result
    }

    /**
     * V101 First-Pairing / Re-Pairing Flow:
     * First verifies the QR against live server. If same or new server, handles seamlessly.
     */
    suspend fun pairWithQr(qrData: QrPairingData): Result<TrustCredentials> {
        val verifyRes = verifyQrSession(qrData)
        if (verifyRes.isFailure) {
            return Result.failure(verifyRes.exceptionOrNull() ?: Exception("فشل التحقق من رمز QR"))
        }

        val session = verifyRes.getOrThrow()
        // If switching from an existing saved server, wait for explicit user confirmation in UI
        if (session.isDifferentFromSavedServer) {
            return Result.failure(Exception("REQUIRES_CONFIRMATION"))
        }

        // Otherwise proceed with pairing immediately
        return executeVerifiedPairing(session)
    }

    /**
     * Rule 11 & V103: RE-BIND TO DIFFERENT SERVER
     */
    suspend fun confirmRebindToMismatchServer(mismatch: ServerMismatchDetails): Result<TrustCredentials> {
        // Clear current server trust upon confirmation
        secureStorage.clearTrust()
        _trustCredentials.value = null
        _serverMismatchDetails.value = null

        val qrData = mismatch.pendingQrData
        return if (qrData.pairingId.isNotBlank() && qrData.token.isNotBlank()) {
            executePairingRequest(
                host = qrData.host,
                port = qrData.port,
                tls = qrData.tls,
                serverId = mismatch.currentServerId,
                serverName = mismatch.restaurantName,
                pairingId = qrData.pairingId,
                token = qrData.token
            )
        } else {
            executeAutoPair(
                serverUrl = mismatch.serverUrl,
                serverId = mismatch.currentServerId,
                restaurantName = mismatch.restaurantName
            )
        }
    }

    private suspend fun executePairingRequest(
        host: String,
        port: Int,
        tls: Boolean,
        serverId: String,
        serverName: String,
        pairingId: String,
        token: String
    ): Result<TrustCredentials> {
        val deviceId = secureStorage.getOrCreateDeviceId()
        val deviceName = "Alamer بدالة"
        val instBinding = secureStorage.getOrCreateInstallationBinding()

        val pairRequest = PairRequest(
            version = 1,
            deviceType = "CallerAssistant",
            serverId = serverId,
            protocolVersion = "1.0",
            pairingId = pairingId,
            token = token,
            deviceId = deviceId,
            deviceName = deviceName,
            installationBinding = instBinding
        )

        val pairResult = httpClient.pair(host, port, tls, pairRequest)
        if (pairResult.isFailure) {
            val err = pairResult.exceptionOrNull()?.message ?: AlamerErrors.formatPairingInvalid()
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

        val trust = TrustCredentials(
            serverId = serverId,
            serverName = serverName,
            serverUrl = pairResponse.serverUrl ?: "http://$host:$port",
            host = host,
            port = port,
            tlsRequired = tls,
            protocolVersion = "1.0",
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
        _untrustedServerPrompt.value = null
        _serverMismatchDetails.value = null

        startSignalR(trust)
        return Result.success(trust)
    }

    /**
     * 14. Reconnect flow with DHCP discovery fallback (Rule 15 & 16)
     */
    fun startAutoReconnect(trust: TrustCredentials) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            _lastError.value = null

            // First verify server info and ServerId match
            val serverInfoRes = httpClient.getServerInfo(trust.host, trust.port, trust.tlsRequired)
            var activeHost = trust.host
            var activePort = trust.port
            var activeUrl = trust.serverUrl

            if (serverInfoRes.isFailure) {
                // Endpoint might have changed due to DHCP (Rule 15)
                val discovered = udpDiscoveryClient.discoverServer(trust.serverId, timeoutMs = 2500)
                    ?: udpDiscoveryClient.probeSubnetForServer(getLocalIpAddress(), trust.serverId)

                if (discovered != null) {
                    activeHost = discovered.host
                    activePort = discovered.port
                    activeUrl = discovered.serverUrl
                    secureStorage.updateEndpoint(activeHost, activePort, activeUrl)
                } else {
                    _lastError.value = AlamerErrors.formatServerUnreachable(activeUrl)
                    _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
                    return@launch
                }
            }

            val reconnectReq = ReconnectRequest(
                deviceId = trust.deviceId,
                installationBinding = trust.installationBinding,
                serverId = trust.serverId,
                callerCredential = trust.callerCredential,
                protocolVersion = trust.protocolVersion
            )

            val reconnectResult = httpClient.reconnect(activeHost, activePort, trust.tlsRequired, reconnectReq)

            if (reconnectResult.isFailure) {
                val ex = reconnectResult.exceptionOrNull()
                if (ex?.message == "SERVER_CONFLICT_409") {
                    // Check if server actually changed
                    val currentInfoRes = httpClient.getServerInfo(activeHost, activePort, trust.tlsRequired)
                    if (currentInfoRes.isSuccess) {
                        val liveInfo = currentInfoRes.getOrThrow()
                        val liveSid = liveInfo.serverId ?: ""
                        if (liveSid.isNotBlank() && !liveSid.equals(trust.serverId, ignoreCase = true)) {
                            _serverMismatchDetails.value = ServerMismatchDetails(
                                restaurantName = liveInfo.displayName,
                                serverUrl = activeUrl,
                                currentServerId = liveSid,
                                qrServerId = trust.serverId,
                                pendingQrData = QrPairingData(
                                    version = "1",
                                    type = "CallerAssistant",
                                    serverId = liveSid,
                                    name = liveInfo.displayName,
                                    host = activeHost,
                                    port = activePort,
                                    tls = trust.tlsRequired,
                                    protocol = "1.0",
                                    pairingId = "",
                                    token = "",
                                    expiryEpochSeconds = 0,
                                    rawUri = activeUrl
                                )
                            )
                            _connectionState.value = ConnectionState.SERVER_ID_MISMATCH
                            return@launch
                        }
                    }
                }
                if (ex is SecurityException) {
                    if (ex.message == "DEVICE_REVOKED") {
                        _connectionState.value = ConnectionState.DEVICE_REVOKED
                        _lastError.value = AlamerErrors.formatDeviceRevoked()
                    } else {
                        _connectionState.value = ConnectionState.CREDENTIAL_INVALID
                        _lastError.value = AlamerErrors.formatCredentialInvalid()
                    }
                } else {
                    _connectionState.value = ConnectionState.SERVER_UNAVAILABLE
                    _lastError.value = ex?.message ?: AlamerErrors.formatServerUnreachable(activeUrl)
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
        posHubManager.start(trust)
    }

    /**
     * Unpair / Reset Trust
     */
    fun unpair() {
        posHubManager.stop()
        secureStorage.clearTrust()
        _trustCredentials.value = null
        _connectionState.value = ConnectionState.NEEDS_PAIRING
        _lastError.value = null
        _serverMismatchDetails.value = null
        _untrustedServerPrompt.value = null
        callManager.clearHistory()
    }

    fun clearMismatchDetails() {
        _serverMismatchDetails.value = null
    }

    fun clearUntrustedServerPrompt() {
        _untrustedServerPrompt.value = null
    }

    fun clearVerifiedQrSession() {
        _verifiedQrSession.value = null
    }

    /**
     * Diagnostics Report (Section 19)
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

        val targetHubUrl = trust?.let { com.example.network.PosHubUrlBuilder.buildPosHubUrl(it.serverUrl) }
            ?: posHubManager.hubUrl.value

        val credDisplay = if (trust != null) {
            val lastFour = if (trust.callerCredential.length >= 4) trust.callerCredential.takeLast(4) else "***"
            "محفوظة وموثوقة (***$lastFour)"
        } else "غير مقترن"

        val report = DiagnosticsReport(
            wifiConnected = isWifi,
            wifiSsid = wifiSsid,
            localIp = localIp,
            savedHost = trust?.host ?: "-",
            savedPort = trust?.port ?: 0,
            serverReachability = reachability,
            serverInfoStatus = infoStatus,
            serverIdMatch = serverIdMatch,
            credentialStatus = credDisplay,
            signalRStatus = posHubManager.connectionState.value.arabicLabel,
            lastError = _lastError.value ?: posHubManager.lastError.value,
            retryCount = posHubManager.retryCount.value,
            lastSyncTime = _lastSyncTimestamp.value,
            hubUrl = targetHubUrl,
            serverUrl = trust?.serverUrl ?: "-",
            lastConnectAttempt = posHubManager.lastConnectAttempt.value,
            lastSuccessfulConnect = posHubManager.lastSuccessfulConnect.value,
            lastDisconnectReason = posHubManager.lastDisconnectReason.value,
            lastAuthResult = posHubManager.lastAuthResult.value
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
