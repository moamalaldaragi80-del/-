package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.network.QrParserAndValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ALAMER Caller", appName)
    }

    @Test
    fun `qr validator accepts compliant taloola-caller URI with port 5000`() {
        val validQr = QrParserAndValidator.buildTestQrString(
            host = "192.168.68.104",
            port = 5000,
            serverId = "SERVER_MAIN_1",
            name = "مطعم السفير",
            ttlSeconds = 3600
        )

        val result = QrParserAndValidator.validate(validQr)
        assertTrue(result.isValid)
        assertNotNull(result.data)
        assertEquals("192.168.68.104", result.data?.host)
        assertEquals(5000, result.data?.port)
        assertEquals("SERVER_MAIN_1", result.data?.serverId)
        assertEquals("CallerAssistant", result.data?.type)
        assertEquals("1.0", result.data?.protocol)
    }

    @Test
    fun `qr validator rejects expired URI`() {
        val expPast = (System.currentTimeMillis() / 1000) - 100
        val expiredQr = "taloola-caller://pair?v=1&type=CallerAssistant&sid=SRV1&name=Taloola&host=192.168.68.104&port=5000&tls=0&proto=1.0&pid=p1&token=tok1&exp=$expPast"

        val result = QrParserAndValidator.validate(expiredQr)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("انتهت صلاحية") })
    }

    @Test
    fun `qr validator rejects wrong device type`() {
        val wrongTypeQr = "taloola-caller://pair?v=1&type=CashierTerminal&sid=SRV1&name=Taloola&host=192.168.68.104&port=5000&tls=0&proto=1.0&pid=p1&token=tok1&exp=${(System.currentTimeMillis() / 1000) + 1000}"

        val result = QrParserAndValidator.validate(wrongTypeQr)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("نوع الجهاز") })
    }
}
