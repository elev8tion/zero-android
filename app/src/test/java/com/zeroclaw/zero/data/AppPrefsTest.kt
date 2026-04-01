package com.zeroclaw.zero.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppPrefsTest {

    private lateinit var prefs: AppPrefs

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPrefs(context)
    }

    // ── Default values ──────────────────────────────────────────────────────────

    @Test
    fun `default proxyUrl is localhost 11435`() {
        assertEquals("http://127.0.0.1:11435", prefs.proxyUrl)
    }

    @Test
    fun `default lastResponse is null`() {
        assertNull(prefs.lastResponse)
    }

    @Test
    fun `DEFAULT_PROXY_URL constant matches default getter`() {
        assertEquals(AppPrefs.DEFAULT_PROXY_URL, prefs.proxyUrl)
    }

    // ── Set/Get roundtrip ───────────────────────────────────────────────────────

    @Test
    fun `set and get proxyUrl roundtrip`() {
        prefs.proxyUrl = "http://192.168.1.100:11435"
        assertEquals("http://192.168.1.100:11435", prefs.proxyUrl)
    }

    @Test
    fun `set and get lastResponse roundtrip`() {
        prefs.lastResponse = "The weather is sunny"
        assertEquals("The weather is sunny", prefs.lastResponse)
    }

    @Test
    fun `overwrite proxyUrl replaces previous value`() {
        prefs.proxyUrl = "http://first.com"
        prefs.proxyUrl = "http://second.com"
        assertEquals("http://second.com", prefs.proxyUrl)
    }

    @Test
    fun `set lastResponse to null clears it`() {
        prefs.lastResponse = "some text"
        prefs.lastResponse = null
        assertNull(prefs.lastResponse)
    }

    // ── Edge cases ──────────────────────────────────────────────────────────────

    @Test
    fun `empty string proxyUrl is stored correctly`() {
        prefs.proxyUrl = ""
        assertEquals("", prefs.proxyUrl)
    }

    @Test
    fun `proxyUrl with trailing slash is stored as-is`() {
        prefs.proxyUrl = "http://192.168.1.1:11435/"
        assertEquals("http://192.168.1.1:11435/", prefs.proxyUrl)
    }

    @Test
    fun `very long lastResponse is stored correctly`() {
        val longText = "a".repeat(10_000)
        prefs.lastResponse = longText
        assertEquals(longText, prefs.lastResponse)
    }

    @Test
    fun `lastResponse with special characters roundtrip`() {
        val special = "Hello! 你好 🌍 <script>alert('xss')</script>"
        prefs.lastResponse = special
        assertEquals(special, prefs.lastResponse)
    }

    // ── Persistence across instances ────────────────────────────────────────────

    @Test
    fun `values persist across AppPrefs instances`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs.proxyUrl = "http://persist.test:1234"
        prefs.lastResponse = "persisted response"

        val prefs2 = AppPrefs(context)
        assertEquals("http://persist.test:1234", prefs2.proxyUrl)
        assertEquals("persisted response", prefs2.lastResponse)
    }
}
