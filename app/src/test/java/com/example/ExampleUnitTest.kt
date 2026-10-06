package com.example

import com.example.network.SignalRClient
import com.example.network.UrlNormalizer
import com.example.telephony.CallManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testPhoneNumberNormalization() {
        val callManager = CallManager(SignalRClient())
        assertEquals("+9647701234567", callManager.normalizePhoneNumber("+964 770 123-4567"))
        assertEquals("07701234567", callManager.normalizePhoneNumber("0770-123-4567"))
        assertEquals("07809876543", callManager.normalizePhoneNumber(" (0780) 987 6543 "))
    }

    @Test
    fun testIdempotencyMaintainsSameCallIdForActiveCall() {
        val callManager = CallManager(SignalRClient())
        val callId1 = callManager.onIncomingCallRinging("07701234567")
        assertNotNull(callId1)

        // Same call event repeated while ringing
        val callId2 = callManager.onIncomingCallRinging("0770 123 4567")
        assertEquals("CallId must remain stable and identical (Rule 19)", callId1, callId2)
    }

    @Test
    fun testUrlNormalizerV100Requirements() {
        // 192.168.68.104:5000 -> http://192.168.68.104:5000
        val norm1 = UrlNormalizer.normalize("192.168.68.104:5000")
        assertNotNull(norm1)
        assertEquals("http://192.168.68.104:5000", norm1?.fullUrl)
        assertEquals("192.168.68.104", norm1?.host)
        assertEquals(5000, norm1?.port)

        // http://192.168.68.104:5000
        val norm2 = UrlNormalizer.normalize("http://192.168.68.104:5000")
        assertNotNull(norm2)
        assertEquals("http://192.168.68.104:5000", norm2?.fullUrl)

        // 192.168.68.104 (defaults to port 5000)
        val norm3 = UrlNormalizer.normalize("192.168.68.104")
        assertNotNull(norm3)
        assertEquals("http://192.168.68.104:5000", norm3?.fullUrl)
        assertEquals(5000, norm3?.port)
    }

    @Test
    fun testV103AutoPairRequestAndResponseParsing() {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

        // Test AutoPairRequest serialization
        val autoPairReq = com.example.model.AutoPairRequest(
            version = 1,
            deviceType = "CallerAssistant",
            deviceId = "test-device-id",
            deviceName = "Alamer بدالة",
            installationBinding = "test-binding",
            protocolVersion = "1.0"
        )
        val reqJson = moshi.adapter(com.example.model.AutoPairRequest::class.java).toJson(autoPairReq)
        assertNotNull(reqJson)
        org.junit.Assert.assertTrue(reqJson.contains("CallerAssistant"))
        org.junit.Assert.assertTrue(reqJson.contains("Alamer بدالة"))

        // Test PairResponse with camelCase (standard ASP.NET Core System.Text.Json)
        val jsonCamel = """
            {
                "success": true,
                "serverId": "server-guid-1234",
                "serverUrl": "http://192.168.68.104:5000",
                "callerCredential": "cred-secret-xyz",
                "protocolVersion": "1.0"
            }
        """.trimIndent()
        val resCamel = moshi.adapter(com.example.model.PairResponse::class.java).fromJson(jsonCamel)
        assertNotNull(resCamel)
        org.junit.Assert.assertTrue(resCamel!!.success)
        assertEquals("server-guid-1234", resCamel.serverId)
        assertEquals("cred-secret-xyz", resCamel.callerCredential)

        // Test PairResponse with PascalCase (.NET legacy MAUI serializer)
        val jsonPascal = """
            {
                "Success": true,
                "ServerId": "server-guid-5678",
                "ServerUrl": "http://192.168.68.104:5000",
                "CallerCredential": "cred-secret-abc",
                "ProtocolVersion": "1.0"
            }
        """.trimIndent()
        val resPascal = moshi.adapter(com.example.model.PairResponse::class.java).fromJson(jsonPascal)
        assertNotNull(resPascal)
        org.junit.Assert.assertTrue(resPascal!!.success)
        assertEquals("server-guid-5678", resPascal.serverId)
        assertEquals("cred-secret-abc", resPascal.callerCredential)
    }

    @Test
    fun testV103ServerInfoParsingBothCases() {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

        val json = """
            {
                "ServerId": "srv-999",
                "callerAssistantEnabled": true,
                "CallerAssistantPairingMode": "DIRECT_LAN_AUTO_PAIR_QR_OPTIONAL",
                "restaurantName": "مطعم النخيل"
            }
        """.trimIndent()
        val info = moshi.adapter(com.example.model.ServerInfoResponse::class.java).fromJson(json)
        assertNotNull(info)
        assertEquals("srv-999", info!!.serverId)
        assertEquals(true, info.callerAssistantEnabled)
        assertEquals("DIRECT_LAN_AUTO_PAIR_QR_OPTIONAL", info.callerAssistantPairingMode)
        assertEquals("مطعم النخيل", info.displayName)
    }

    @Test
    fun testV106CapabilitiesArrayAndBitmaskParsing() {
        // V106 standard: capabilities: ["CallerAssistant"]
        val jsonArray = """
            {
                "success": true,
                "serverId": "server-106",
                "callerCredential": "super-secret-token",
                "capabilities": ["CallerAssistant", "OrderNotification"]
            }
        """.trimIndent()
        val result1 = com.example.network.PairingResultParser.parse(jsonArray)
        org.junit.Assert.assertTrue(result1.success)
        assertEquals(listOf("CallerAssistant", "OrderNotification"), result1.capabilities)
        assertEquals("server-106", result1.serverId)
        assertEquals("super-secret-token", result1.callerCredential)

        // Legacy contract: capabilities: 2048 (number/bitmask) - MUST NOT throw Expected BEGIN_ARRAY but was NUMBER
        val jsonNumber = """
            {
                "Success": true,
                "ServerId": "server-legacy",
                "CallerCredential": "legacy-token-123",
                "capabilities": 2048
            }
        """.trimIndent()
        val result2 = com.example.network.PairingResultParser.parse(jsonNumber)
        org.junit.Assert.assertTrue(result2.success)
        assertEquals(listOf("CallerAssistant"), result2.capabilities)
        assertEquals("server-legacy", result2.serverId)

        // Empty array [] - MUST NOT fail pairing
        val jsonEmpty = """
            {
                "success": true,
                "serverId": "server-empty",
                "capabilities": []
            }
        """.trimIndent()
        val result3 = com.example.network.PairingResultParser.parse(jsonEmpty)
        org.junit.Assert.assertTrue(result3.success)
        assertEquals(emptyList<String>(), result3.capabilities)

        // Redaction verification
        val redacted = com.example.network.PairingResultParser.redactSensitiveJson(jsonArray)
        org.junit.Assert.assertFalse(redacted.contains("super-secret-token"))
        org.junit.Assert.assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun testPosHubUrlBuilderSection13Compliance() {
        val normal = com.example.network.PosHubUrlBuilder.buildPosHubUrl("http://192.168.68.104:5000")
        assertEquals("http://192.168.68.104:5000/posHub", normal)

        val trailingSlash = com.example.network.PosHubUrlBuilder.buildPosHubUrl("http://192.168.68.104:5000/")
        assertEquals("http://192.168.68.104:5000/posHub", trailingSlash)

        val alreadyHasHub = com.example.network.PosHubUrlBuilder.buildPosHubUrl("http://192.168.68.104:5000/posHub")
        assertEquals("http://192.168.68.104:5000/posHub", alreadyHasHub)

        val negotiateUrl = com.example.network.PosHubUrlBuilder.buildNegotiateUrl("http://192.168.68.104:5000")
        assertEquals("http://192.168.68.104:5000/posHub/negotiate?negotiateVersion=1", negotiateUrl)

        val wsUrlWithToken = com.example.network.PosHubUrlBuilder.buildWebSocketUrl("http://192.168.68.104:5000", "myToken123")
        assertEquals("ws://192.168.68.104:5000/posHub?id=myToken123", wsUrlWithToken)

        val wsUrlNoToken = com.example.network.PosHubUrlBuilder.buildWebSocketUrl("http://192.168.68.104:5000")
        assertEquals("ws://192.168.68.104:5000/posHub", wsUrlNoToken)
    }

    @Test
    fun testPosHubStateMachineProgressionSection6() {
        // State progression: DISCONNECTED -> CONNECTING -> CONNECTED -> AUTHENTICATING -> READY
        val states = listOf(
            com.example.model.PosHubState.DISCONNECTED,
            com.example.model.PosHubState.CONNECTING,
            com.example.model.PosHubState.CONNECTED,
            com.example.model.PosHubState.AUTHENTICATING,
            com.example.model.PosHubState.READY
        )
        org.junit.Assert.assertFalse(states[0].isOnline)
        org.junit.Assert.assertFalse(states[1].isOnline)
        org.junit.Assert.assertFalse(states[2].isOnline)
        org.junit.Assert.assertFalse(states[3].isOnline)
        org.junit.Assert.assertTrue(states[4].isOnline)

        // On disconnect: READY -> RECONNECTING -> CONNECTED -> AUTHENTICATING -> READY
        val reconnectFlow = listOf(
            com.example.model.PosHubState.READY,
            com.example.model.PosHubState.RECONNECTING,
            com.example.model.PosHubState.CONNECTED,
            com.example.model.PosHubState.AUTHENTICATING,
            com.example.model.PosHubState.READY
        )
        assertEquals("READY", reconnectFlow.first().name)
        assertEquals("RECONNECTING", reconnectFlow[1].name)
        assertEquals("READY", reconnectFlow.last().name)
    }

    @Test
    fun testAuthenticateCallerAssistantResultParsing() {
        // Typical server result payload from AuthenticateCallerAssistant
        val authResultJson = """
            {
                "success": true,
                "deviceId": "dev-001",
                "deviceStatus": "Approved",
                "capabilities": ["CallerAssistant"],
                "callerCredential": "cred-valid-99"
            }
        """.trimIndent()

        val parsed = com.example.network.PairingResultParser.parse(authResultJson)
        org.junit.Assert.assertTrue(parsed.success)
        assertEquals("Approved", parsed.deviceStatus)
        assertEquals(listOf("CallerAssistant"), parsed.capabilities)
        assertEquals("cred-valid-99", parsed.callerCredential)
    }
}
