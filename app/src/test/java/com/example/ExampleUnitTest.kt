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
}
