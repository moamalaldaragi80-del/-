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
}
