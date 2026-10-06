package com.example.network

import android.net.Uri
import com.example.model.QrPairingData
import com.example.model.QrValidationResult

/**
 * 7. QR VALIDATION:
 * Validates the 12 QR checks according to ALAMER / Taloola Protocol 1.0.
 */
object QrParserAndValidator {

    const val EXPECTED_SCHEME = "taloola-caller"
    const val EXPECTED_DEVICE_TYPE = "CallerAssistant"
    const val EXPECTED_PROTOCOL = "1.0"
    const val DEFAULT_SERVER_PORT = 5000
    const val DEFAULT_BRIDGE_PORT = 5000

    fun validate(qrText: String, currentTimeEpochSeconds: Long = System.currentTimeMillis() / 1000): QrValidationResult {
        val trimmed = qrText.trim()
        val errors = mutableListOf<String>()
        val checkSummary = mutableMapOf<String, Boolean>()

        // 1. Parse URI
        val uri: Uri? = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            null
        }

        if (uri == null) {
            return QrValidationResult(
                isValid = false,
                errors = listOf("فشل تحليل نص رمز QR (صيغة URI غير صالحة)")
            )
        }
        checkSummary["parse_uri"] = true

        // 2. Validate scheme
        val scheme = uri.scheme?.lowercase()
        val isSchemeValid = scheme == EXPECTED_SCHEME
        checkSummary["scheme"] = isSchemeValid
        if (!isSchemeValid) {
            errors.add("مخطط QR غير صحيح: متوقع '$EXPECTED_SCHEME' ووجد '$scheme'")
        }

        // 3. Validate path
        val path = uri.path?.trimStart('/') ?: uri.host ?: ""
        // Scheme taloola-caller://pair? -> host might be 'pair' or path might be '/pair'
        val isPairPath = path.equals("pair", ignoreCase = true) || uri.authority.equals("pair", ignoreCase = true)
        checkSummary["path"] = isPairPath
        if (!isPairPath) {
            errors.add("مسار الاقتران غير صحيح: متوقع 'pair'")
        }

        // 4. Validate version
        val v = uri.getQueryParameter("v") ?: ""
        val isVersionValid = v == "1" || v == "1.0"
        checkSummary["version"] = isVersionValid
        if (!isVersionValid) {
            errors.add("إصدار رمز QR غير مدعوم: '$v'")
        }

        // 5. Validate device type
        val type = uri.getQueryParameter("type") ?: ""
        val isTypeValid = type.equals(EXPECTED_DEVICE_TYPE, ignoreCase = true)
        checkSummary["device_type"] = isTypeValid
        if (!isTypeValid) {
            errors.add("نوع الجهاز غير متطابق: متوقع '$EXPECTED_DEVICE_TYPE' ووجد '$type'")
        }

        // 6. Validate host
        val host = uri.getQueryParameter("host")?.trim() ?: ""
        val isHostValid = host.isNotBlank() && !host.contains(" ")
        checkSummary["host"] = isHostValid
        if (!isHostValid) {
            errors.add("عنوان الخادم (Host) مفقود أو غير صالح")
        }

        // 7. Validate port
        val portStr = uri.getQueryParameter("port") ?: "$DEFAULT_BRIDGE_PORT"
        val port = portStr.toIntOrNull()
        val isPortValid = port != null && port in 1..65535
        checkSummary["port"] = isPortValid
        if (!isPortValid) {
            errors.add("رقم المنفذ غير صالح: '$portStr'")
        }

        // 8. Validate protocol
        val proto = uri.getQueryParameter("proto") ?: ""
        val isProtoValid = proto == EXPECTED_PROTOCOL
        checkSummary["protocol"] = isProtoValid
        if (!isProtoValid) {
            errors.add("إصدار البروتوكول غير متطابق: متوقع '$EXPECTED_PROTOCOL' ووجد '$proto'")
        }

        // 9. Validate expiry
        val expStr = uri.getQueryParameter("exp") ?: "0"
        val exp = expStr.toLongOrNull() ?: 0L
        val isExpiryValid = exp > 0 && exp >= currentTimeEpochSeconds
        checkSummary["expiry"] = isExpiryValid
        if (!isExpiryValid) {
            errors.add(com.example.model.AlamerErrors.formatPairingExpired())
        }

        // 10. Validate ServerId
        val sid = uri.getQueryParameter("sid")?.trim() ?: ""
        val isSidValid = sid.isNotBlank()
        checkSummary["server_id"] = isSidValid
        if (!isSidValid) {
            errors.add("معرّف الخادم (ServerId) مفقود")
        }

        // 11. Validate PairingId
        val pid = uri.getQueryParameter("pid")?.trim() ?: ""
        val isPidValid = pid.isNotBlank()
        checkSummary["pairing_id"] = isPidValid
        if (!isPidValid) {
            errors.add("معرّف الاقتران (PairingId) مفقود")
        }

        // 12. Validate token
        val token = uri.getQueryParameter("token")?.trim() ?: ""
        val isTokenValid = token.isNotBlank()
        checkSummary["token"] = isTokenValid
        if (!isTokenValid) {
            errors.add("رمز التوثيق المؤقت (Token) مفقود")
        }

        val name = uri.getQueryParameter("name") ?: "Taloola"
        val tls = uri.getQueryParameter("tls") == "1"

        val isValid = errors.isEmpty()
        val data = if (isValid) {
            QrPairingData(
                version = v,
                type = type,
                serverId = sid,
                name = name,
                host = host,
                port = port ?: DEFAULT_SERVER_PORT,
                tls = tls,
                protocol = proto,
                pairingId = pid,
                token = token,
                expiryEpochSeconds = exp,
                rawUri = trimmed
            )
        } else null

        return QrValidationResult(
            isValid = isValid,
            data = data,
            errors = errors,
            checkSummary = checkSummary
        )
    }

    /**
     * Helper to generate a compliant QR string for testing/mocking in diagnostics & debug.
     */
    fun buildTestQrString(
        host: String = "192.168.68.104",
        port: Int = 5000,
        serverId: String = "TALOOLA-SRV-904",
        name: String = "مطعم السفير",
        ttlSeconds: Long = 3600
    ): String {
        val exp = (System.currentTimeMillis() / 1000) + ttlSeconds
        val pid = "pair-" + java.util.UUID.randomUUID().toString().take(8)
        val token = "tok-" + java.util.UUID.randomUUID().toString().replace("-", "")
        return "$EXPECTED_SCHEME://pair?v=1&type=$EXPECTED_DEVICE_TYPE&sid=$serverId&name=$name&host=$host&port=$port&tls=0&proto=$EXPECTED_PROTOCOL&pid=$pid&token=$token&exp=$exp"
    }
}
