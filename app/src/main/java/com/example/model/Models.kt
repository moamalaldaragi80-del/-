package com.example.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 29. CONNECTION STATES
 */
enum class ConnectionState(val arabicLabel: String, val englishLabel: String) {
    UNINITIALIZED("غير مهيأ", "Uninitialized"),
    NEEDS_PAIRING("بانتظار مسح رمز QR", "Needs Pairing"),
    PAIRING("جارٍ التحقق والاقتران...", "Pairing"),
    PAIRING_SUCCESS("تم الاقتران بنجاح", "Pairing Success"),
    CONNECTING("جارٍ الاتصال بالبدالة...", "Connecting"),
    AUTHENTICATING("جارٍ المصادقة مع Taloola...", "Authenticating"),
    READY("متصل وجاهز للاستقبال", "Ready / Connected"),
    NETWORK_UNAVAILABLE("شبكة Wi-Fi غير متاحة", "Network Unavailable"),
    SERVER_UNAVAILABLE("تعذر الوصول إلى الخادم", "Server Unavailable"),
    SERVER_ID_MISMATCH("الخادم لا يطابق الخادم المقصود", "Server ID Mismatch"),
    CREDENTIAL_INVALID("بيانات الاعتماد غير صالحة", "Credential Invalid"),
    DEVICE_REVOKED("تم إلغاء ترخيص هذا الجهاز", "Device Revoked"),
    PROTOCOL_MISMATCH("عدم تطابق إصدار البروتوكول", "Protocol Mismatch"),
    NEEDS_REPAIR("يتطلب إعادة الربط والاقتران", "Needs Re-pair")
}

/**
 * QR Code parsed data: taloola-caller://pair
 */
data class QrPairingData(
    val version: String,
    val type: String,
    val serverId: String,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val protocol: String,
    val pairingId: String,
    val token: String,
    val expiryEpochSeconds: Long,
    val rawUri: String
)

data class QrValidationResult(
    val isValid: Boolean,
    val data: QrPairingData? = null,
    val errors: List<String> = emptyList(),
    val checkSummary: Map<String, Boolean> = emptyMap()
)

/**
 * Server Info Response: GET /api/server/info
 */
@JsonClass(generateAdapter = true)
data class ServerInfoResponse(
    @Json(name = "ServerId") val serverId: String? = null,
    @Json(name = "ProtocolVersion") val protocolVersion: String? = null,
    @Json(name = "SessionActive") val sessionActive: Boolean? = null,
    @Json(name = "Port") val port: Int? = null,
    @Json(name = "ServerName") val serverName: String? = null,
    @Json(name = "RestaurantName") val restaurantName: String? = null,
    @Json(name = "ServerVersion") val serverVersion: String? = null,
    @Json(name = "CallerAssistantEnabled") val callerAssistantEnabled: Boolean? = null,
    @Json(name = "CallerAssistantPairingMode") val callerAssistantPairingMode: String? = null,
    @Json(name = "CallerAssistantPort") val callerAssistantPort: Int? = null,
    @Json(name = "CallerAssistantUrl") val callerAssistantUrl: String? = null
) {
    val displayName: String
        get() = restaurantName ?: serverName ?: "تعلولة"
}

/**
 * Step 3: GET /api/caller-assistant/pair-info?pid=<QR.pid>
 */
@JsonClass(generateAdapter = true)
data class PairInfoResponse(
    @Json(name = "Success") val success: Boolean = true,
    @Json(name = "ServerId") val serverId: String? = null,
    @Json(name = "RestaurantName") val restaurantName: String? = null,
    @Json(name = "ServerName") val serverName: String? = null,
    @Json(name = "ServerUrl") val serverUrl: String? = null,
    @Json(name = "PairingId") val pairingId: String? = null,
    @Json(name = "ProtocolVersion") val protocolVersion: String? = null,
    @Json(name = "ExpiresAtUtc") val expiresAtUtc: String? = null,
    @Json(name = "ErrorMessage") val errorMessage: String? = null
) {
    val displayName: String
        get() = restaurantName ?: serverName ?: "تعلولة"
}

object AlamerErrors {
    const val SERVER_UNREACHABLE = "SERVER_UNREACHABLE"
    const val SERVER_ID_MISMATCH = "SERVER_ID_MISMATCH"
    const val PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH"
    const val PAIRING_EXPIRED = "PAIRING_EXPIRED"
    const val PAIRING_INVALID = "PAIRING_INVALID"
    const val PAIRING_ID_INVALID = "PAIRING_ID_INVALID"
    const val PAIRING_ALREADY_CONSUMED = "PAIRING_ALREADY_CONSUMED"
    const val LAN_ACCESS_DENIED = "LAN_ACCESS_DENIED"
    const val CREDENTIAL_INVALID = "CREDENTIAL_INVALID"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val WRONG_NETWORK = "WRONG_NETWORK"

    fun formatServerUnreachable(url: String): String = "تعذر الوصول إلى الخادم على:\n$url"
    fun formatServerIdMismatch(qrSid: String = "", currentSid: String = "", url: String = ""): String =
        "الرمز لا يخص الخادم الموجود على عنوان الشبكة هذا.\nالعنوان: $url\nQR ServerId: ${qrSid.take(12)}\nالخادم الفعلي: ${currentSid.take(12)}"
    fun formatProtocolMismatch(): String = "إصدار التطبيق غير متوافق مع TaloolaPos."
    fun formatPairingExpired(): String = "انتهت صلاحية رمز الربط. اطلب QR جديداً."
    fun formatPairingInvalid(): String = "رمز الربط غير صالح."
    fun formatPairingIdInvalid(): String = "رمز الربط لا يخص جلسة QR الحالية."
    fun formatPairingAlreadyConsumed(): String = "تم استخدام هذا الرمز مسبقاً."
    fun formatLanAccessDenied(): String = "الهاتف ليس على شبكة LAN الخاصة بالمطعم."
    fun formatCredentialInvalid(): String = "بيانات الثقة المحفوظة غير صالحة. أعد الربط عبر QR."
    fun formatDeviceRevoked(): String = "تم إلغاء اعتماد هذا الجهاز من TaloolaPos."
    fun formatWrongNetwork(): String = "الهاتف متصل بشبكة مختلفة عن شبكة المطعم."
}

data class ServerMismatchDetails(
    val restaurantName: String,
    val serverUrl: String,
    val currentServerId: String,
    val qrServerId: String,
    val pendingQrData: QrPairingData
)

data class UntrustedServerPrompt(
    val restaurantName: String,
    val serverUrl: String,
    val serverId: String
)

data class VerifiedQrSession(
    val qrData: QrPairingData,
    val restaurantName: String,
    val serverUrl: String,
    val serverId: String,
    val isDifferentFromSavedServer: Boolean
)

/**
 * POST /api/caller-assistant/pair Request
 */
@JsonClass(generateAdapter = true)
data class PairRequest(
    @Json(name = "Version") val version: Int = 1,
    @Json(name = "DeviceType") val deviceType: String = "CallerAssistant",
    @Json(name = "ServerId") val serverId: String,
    @Json(name = "ProtocolVersion") val protocolVersion: String = "1.0",
    @Json(name = "PairingId") val pairingId: String,
    @Json(name = "Token") val token: String,
    @Json(name = "DeviceId") val deviceId: String,
    @Json(name = "DeviceName") val deviceName: String,
    @Json(name = "InstallationBinding") val installationBinding: String
)

/**
 * POST /api/caller-assistant/pair Response
 */
@JsonClass(generateAdapter = true)
data class PairResponse(
    @Json(name = "Success") val success: Boolean = false,
    @Json(name = "ServerId") val serverId: String? = null,
    @Json(name = "ServerUrl") val serverUrl: String? = null,
    @Json(name = "ProtocolVersion") val protocolVersion: String? = null,
    @Json(name = "DeviceId") val deviceId: String? = null,
    @Json(name = "DeviceName") val deviceName: String? = null,
    @Json(name = "InstallationBinding") val installationBinding: String? = null,
    @Json(name = "CallerCredential") val callerCredential: String? = null,
    @Json(name = "DeviceStatus") val deviceStatus: String? = null,
    @Json(name = "Capabilities") val capabilities: List<String>? = null,
    @Json(name = "ErrorMessage") val errorMessage: String? = null
)

/**
 * POST /api/caller-assistant/reconnect Request
 */
@JsonClass(generateAdapter = true)
data class ReconnectRequest(
    @Json(name = "DeviceId") val deviceId: String,
    @Json(name = "InstallationBinding") val installationBinding: String,
    @Json(name = "ServerId") val serverId: String,
    @Json(name = "CallerCredential") val callerCredential: String,
    @Json(name = "ProtocolVersion") val protocolVersion: String = "1.0"
)

@JsonClass(generateAdapter = true)
data class ReconnectResponse(
    @Json(name = "Success") val success: Boolean = false,
    @Json(name = "ServerId") val serverId: String? = null,
    @Json(name = "ServerUrl") val serverUrl: String? = null,
    @Json(name = "DeviceStatus") val deviceStatus: String? = null,
    @Json(name = "ErrorMessage") val errorMessage: String? = null
)

/**
 * SignalR AuthenticateCallerAssistant Request
 */
@JsonClass(generateAdapter = true)
data class SignalRAuthRequest(
    @Json(name = "DeviceId") val deviceId: String,
    @Json(name = "DeviceName") val deviceName: String,
    @Json(name = "InstallationBinding") val installationBinding: String,
    @Json(name = "CallerCredential") val callerCredential: String,
    @Json(name = "ProtocolVersion") val protocolVersion: String = "1.0",
    @Json(name = "Platform") val platform: String = "Android"
)

/**
 * SignalR RecordCallerCall Request
 */
@JsonClass(generateAdapter = true)
data class CallRecordRequest(
    @Json(name = "CallId") val callId: String,
    @Json(name = "Phone") val phone: String,
    @Json(name = "NormalizedPhone") val normalizedPhone: String,
    @Json(name = "Direction") val direction: String = "incoming",
    @Json(name = "StartedAtUtc") val startedAtUtc: String
)

/**
 * SignalR CallerCustomerContext
 */
@JsonClass(generateAdapter = true)
data class CallerCustomerContext(
    @Json(name = "CallId") val callId: String? = null,
    @Json(name = "CustomerId") val customerId: String? = null,
    @Json(name = "Phone") val phone: String? = null,
    @Json(name = "NormalizedPhone") val normalizedPhone: String? = null,
    @Json(name = "Name") val name: String? = null,
    @Json(name = "Status") val status: String? = null,
    @Json(name = "CurrentSessionId") val currentSessionId: String? = null,
    @Json(name = "LastCall") val lastCall: String? = null,
    @Json(name = "MinutesSinceLastCall") val minutesSinceLastCall: Int? = null,
    @Json(name = "LastOrder") val lastOrder: String? = null,
    @Json(name = "RecentOrders") val recentOrders: List<String>? = null,
    @Json(name = "OrderCount") val orderCount: Int? = null,
    @Json(name = "Area") val area: String? = null,
    @Json(name = "Address") val address: String? = null,
    @Json(name = "Latitude") val latitude: Double? = null,
    @Json(name = "Longitude") val longitude: Double? = null,
    @Json(name = "HasLocation") val hasLocation: Boolean? = null,
    @Json(name = "Notes") val notes: String? = null,
    @Json(name = "RetrievedAtUtc") val retrievedAtUtc: String? = null
)

/**
 * Persistent Trust stored securely
 */
data class TrustCredentials(
    val serverId: String,
    val serverName: String,
    val serverUrl: String,
    val host: String,
    val port: Int,
    val tlsRequired: Boolean,
    val protocolVersion: String,
    val deviceId: String,
    val deviceName: String,
    val installationBinding: String,
    val callerCredential: String,
    val hubPath: String = "/posHub",
    val pairedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Call entry in local call history / buffer
 */
data class CallHistoryItem(
    val callId: String,
    val phone: String,
    val normalizedPhone: String,
    val direction: String,
    val startedAtUtc: String,
    val timestampMs: Long,
    val isDispatchedToTaloola: Boolean,
    val customerContext: CallerCustomerContext? = null
)

/**
 * Diagnostics information
 */
data class DiagnosticsReport(
    val wifiConnected: Boolean = false,
    val wifiSsid: String = "غير متصل",
    val localIp: String = "127.0.0.1",
    val savedHost: String = "-",
    val savedPort: Int = 0,
    val serverReachability: Boolean = false,
    val serverInfoStatus: String = "-",
    val serverIdMatch: Boolean = false,
    val credentialStatus: String = "غير مهيأ",
    val signalRStatus: String = "غير متصل",
    val lastError: String? = null,
    val retryCount: Int = 0,
    val lastSyncTime: String = "-"
)
