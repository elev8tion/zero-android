package com.zeroclaw.zero.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for AppPrefs — validates real SharedPreferences on device.
 * Catches issues that Robolectric's in-memory implementation might miss.
 */
@RunWith(AndroidJUnit4::class)
class AppPrefsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPrefs

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clear prefs before each test for isolation
        context.getSharedPreferences("zero_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = AppPrefs(context)
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun defaultProxyUrlOnFreshInstall() {
        assertEquals("http://127.0.0.1:11435", prefs.proxyUrl)
    }

    @Test
    fun defaultLastResponseIsNull() {
        assertNull(prefs.lastResponse)
    }

    // ── Set/Get roundtrip ─────────────────────────────────────────────────────

    @Test
    fun setGetProxyUrlRoundtrip() {
        prefs.proxyUrl = "http://10.0.2.2:11435"
        assertEquals("http://10.0.2.2:11435", prefs.proxyUrl)
    }

    @Test
    fun setGetLastResponseRoundtrip() {
        prefs.lastResponse = "Hello from instrumented test"
        assertEquals("Hello from instrumented test", prefs.lastResponse)
    }

    // ── Persistence across instances ──────────────────────────────────────────

    @Test
    fun valuesSurviveAcrossAppPrefsInstances() {
        prefs.proxyUrl = "http://persist-test:9999"
        prefs.lastResponse = "persisted value"

        // Create a fresh AppPrefs pointing to the same SharedPreferences file
        val prefs2 = AppPrefs(context)
        assertEquals("http://persist-test:9999", prefs2.proxyUrl)
        assertEquals("persisted value", prefs2.lastResponse)
    }

    @Test
    fun clearLastResponseSurvivesAcrossInstances() {
        prefs.lastResponse = "temporary"
        prefs.lastResponse = null

        val prefs2 = AppPrefs(context)
        assertNull(prefs2.lastResponse)
    }
}
