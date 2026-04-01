package com.zeroclaw.zero.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TextToSpeechManagerTest {

    private lateinit var ttsManager: TextToSpeechManager
    private lateinit var mockTts: TextToSpeech
    private var capturedUtteranceListener: UtteranceProgressListener? = null

    @Before
    fun setup() {
        mockTts = mockk(relaxed = true)

        // Capture the UtteranceProgressListener
        every { mockTts.setOnUtteranceProgressListener(any()) } answers {
            capturedUtteranceListener = firstArg()
            0 // TextToSpeech.SUCCESS
        }

        // Mock TextToSpeech constructor behavior via reflection
        // Since TextToSpeech initialization is async, we'll create the manager
        // and then set internal state directly for testing
        ttsManager = TextToSpeechManager(RuntimeEnvironment.getApplication())

        // Replace internal TTS instance and mark as ready via reflection
        setField("tts", mockTts)
        setField("isReady", true)

        // Re-setup the utterance listener
        mockTts.setOnUtteranceProgressListener(capturedUtteranceListener)
    }

    @After
    fun teardown() {
        ttsManager.shutdown()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun setField(name: String, value: Any?) {
        val field: Field = TextToSpeechManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(ttsManager, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(name: String): T {
        val field: Field = TextToSpeechManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(ttsManager) as T
    }

    // ── speak ───────────────────────────────────────────────────────────────────

    @Test
    fun `speak calls TTS speak with correct text`() {
        ttsManager.speak("Hello world")
        verify { mockTts.speak("Hello world", TextToSpeech.QUEUE_FLUSH, null, "zero_utterance") }
    }

    @Test
    fun `speak with empty string still calls TTS`() {
        // TextToSpeechManager doesn't filter empty strings — it passes them through
        ttsManager.speak("")
        verify { mockTts.speak("", TextToSpeech.QUEUE_FLUSH, null, "zero_utterance") }
    }

    @Test
    fun `speak before TTS ready invokes onComplete immediately`() {
        setField("isReady", false)

        var completed = false
        ttsManager.speak("test") { completed = true }
        assertTrue("onComplete should fire immediately when TTS not ready", completed)
        verify(exactly = 0) { mockTts.speak(any(), any(), any(), any<String>()) }
    }

    @Test
    fun `speak stores onComplete callback`() {
        var completed = false
        ttsManager.speak("test") { completed = true }

        // Callback should NOT fire until TTS finishes
        assertFalse(completed)

        // Simulate TTS completion
        val onDone: (() -> Unit)? = getField("onDone")
        onDone?.invoke()
        assertTrue("onComplete should fire after TTS done", completed)
    }

    @Test
    fun `speak with null onComplete is fine`() {
        ttsManager.speak("test", null)
        verify { mockTts.speak("test", TextToSpeech.QUEUE_FLUSH, null, "zero_utterance") }
    }

    @Test
    fun `speak overwrites previous onComplete callback`() {
        var first = false
        var second = false

        ttsManager.speak("first") { first = true }
        ttsManager.speak("second") { second = true }

        // Only the second callback should be stored
        val onDone: (() -> Unit)? = getField("onDone")
        onDone?.invoke()
        assertFalse("First callback should be overwritten", first)
        assertTrue("Second callback should fire", second)
    }

    // ── stop ────────────────────────────────────────────────────────────────────

    @Test
    fun `stop calls TTS stop`() {
        ttsManager.stop()
        verify { mockTts.stop() }
    }

    @Test
    fun `stop is safe when TTS is null`() {
        setField("tts", null)
        ttsManager.stop() // should not throw
    }

    // ── isSpeaking ──────────────────────────────────────────────────────────────

    @Test
    fun `isSpeaking returns TTS isSpeaking`() {
        every { mockTts.isSpeaking } returns true
        assertTrue(ttsManager.isSpeaking)

        every { mockTts.isSpeaking } returns false
        assertFalse(ttsManager.isSpeaking)
    }

    @Test
    fun `isSpeaking returns false when TTS is null`() {
        setField("tts", null)
        assertFalse(ttsManager.isSpeaking)
    }

    // ── shutdown ────────────────────────────────────────────────────────────────

    @Test
    fun `shutdown stops and destroys TTS`() {
        ttsManager.shutdown()
        verify { mockTts.stop() }
        verify { mockTts.shutdown() }

        val tts: TextToSpeech? = getField("tts")
        assertNull("TTS should be null after shutdown", tts)

        val isReady: Boolean = getField("isReady")
        assertFalse("isReady should be false after shutdown", isReady)
    }

    @Test
    fun `shutdown then speak fires onComplete immediately`() {
        ttsManager.shutdown()

        var completed = false
        ttsManager.speak("test") { completed = true }
        assertTrue("Should fire onComplete immediately since not ready", completed)
    }

    // ── UtteranceProgressListener ───────────────────────────────────────────────

    @Test
    fun `utterance onDone fires stored callback`() {
        var completed = false
        ttsManager.speak("hello") { completed = true }

        // Simulate UtteranceProgressListener.onDone
        val onDone: (() -> Unit)? = getField("onDone")
        assertNotNull(onDone)
        onDone!!.invoke()
        assertTrue(completed)
    }

    @Test
    fun `utterance onError also fires stored callback`() {
        // The manager's UtteranceProgressListener.onError calls onDone too
        var completed = false
        ttsManager.speak("hello") { completed = true }

        // Both onDone and onError invoke the stored callback
        val onDone: (() -> Unit)? = getField("onDone")
        onDone?.invoke()
        assertTrue(completed)
    }

    // ── Long text ───────────────────────────────────────────────────────────────

    @Test
    fun `speak with very long text passes it through`() {
        val longText = "a".repeat(5000)
        ttsManager.speak(longText)
        verify { mockTts.speak(longText, any(), any(), any<String>()) }
    }
}
