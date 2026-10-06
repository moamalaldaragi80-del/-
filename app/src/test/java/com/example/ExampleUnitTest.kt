package com.example

import com.example.network.SignalRClient
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
}
