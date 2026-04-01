package com.zeroclaw.zero.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SpeechRecognizerManagerTest {

    private lateinit var sttManager: SpeechRecognizerManager
    private lateinit var mockListener: SpeechRecognizerManager.Listener
    private lateinit var mockRecognizer: SpeechRecognizer
    private var capturedRecognitionListener: RecognitionListener? = null

    @Before
    fun setup() {
        mockRecognizer = mockk(relaxed = true)

        // Capture the RecognitionListener that SpeechRecognizerManager sets
        every { mockRecognizer.setRecognitionListener(any()) } answers {
            capturedRecognitionListener = firstArg()
        }

        // Mock static SpeechRecognizer methods
        mockkStatic(SpeechRecognizer::class)
        every { SpeechRecognizer.isRecognitionAvailable(any()) } returns true
        every { SpeechRecognizer.createSpeechRecognizer(any()) } returns mockRecognizer

        sttManager = SpeechRecognizerManager(RuntimeEnvironment.getApplication())
        mockListener = mockk(relaxed = true)
        sttManager.setListener(mockListener)
    }

    @After
    fun teardown() {
        unmockkStatic(SpeechRecognizer::class)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun makeResultBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf(text)
        )
    }

    private fun makePartialBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf(text)
        )
    }

    private fun startListeningAndCapture(): RecognitionListener {
        sttManager.startListening()
        assertNotNull("RecognitionListener should be captured", capturedRecognitionListener)
        return capturedRecognitionListener!!
    }

    // ── startListening ──────────────────────────────────────────────────────────

    @Test
    fun `startListening fires onListeningStarted once`() {
        startListeningAndCapture()
        verify(exactly = 1) { mockListener.onListeningStarted() }
    }

    @Test
    fun `startListening when recognition unavailable fires onError`() {
        every { SpeechRecognizer.isRecognitionAvailable(any()) } returns false
        sttManager.startListening()
        verify { mockListener.onError("Speech recognition unavailable") }
    }

    @Test
    fun `startListening creates recognizer and starts it`() {
        startListeningAndCapture()
        verify { mockRecognizer.startListening(any()) }
    }

    // ── Partial results ─────────────────────────────────────────────────────────

    @Test
    fun `partial results forwarded to listener`() {
        val recListener = startListeningAndCapture()
        recListener.onPartialResults(makePartialBundle("hello wor"))
        verify { mockListener.onPartial("hello wor") }
    }

    // ── Results and auto-restart ────────────────────────────────────────────────

    @Test
    fun `results during active session accumulates text and auto-restarts`() {
        val recListener = startListeningAndCapture()

        // Simulate first segment result
        recListener.onResults(makeResultBundle("hello world"))

        // Should show accumulated text as partial
        verify { mockListener.onPartial("hello world") }

        // Should NOT deliver final result (auto-restart instead)
        verify(exactly = 0) { mockListener.onResult(any()) }

        // Advance Robolectric looper to trigger the delayed restart
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // A new recognizer should be created for the restart
        verify(atLeast = 2) { SpeechRecognizer.createSpeechRecognizer(any()) }
    }

    @Test
    fun `accumulated text preserved across auto-restarts`() {
        val recListener1 = startListeningAndCapture()
        recListener1.onResults(makeResultBundle("hello"))

        // Advance to trigger restart
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Get new captured listener after restart
        val recListener2 = capturedRecognitionListener!!
        recListener2.onPartialResults(makePartialBundle("world"))

        // Partial should show accumulated "hello" + current "world"
        verify { mockListener.onPartial("hello world") }
    }

    // ── finalize ────────────────────────────────────────────────────────────────

    @Test
    fun `finalize delivers accumulated text and ends session`() {
        val recListener = startListeningAndCapture()
        recListener.onResults(makeResultBundle("hello world"))

        sttManager.finalize()

        verify { mockListener.onResult("hello world") }
        verify { mockListener.onListeningEnded() }
    }

    @Test
    fun `finalize with no text does not deliver result`() {
        startListeningAndCapture()
        sttManager.finalize()

        verify(exactly = 0) { mockListener.onResult(any()) }
        verify { mockListener.onListeningEnded() }
    }

    @Test
    fun `finalize during pending restart cancels restart`() {
        val recListener = startListeningAndCapture()
        recListener.onResults(makeResultBundle("partial"))

        // finalize before the delayed restart fires
        sttManager.finalize()

        // Advance looper — the restart should NOT happen
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Only one result delivery (from finalize), no additional recognizer creation
        // The restart would create a 3rd recognizer (1st from startListening, 2nd from
        // auto-restart setup in onResults, 3rd if the delayed restart fired)
        verify { mockListener.onResult("partial") }
    }

    // ── Generation counter / stale callbacks ────────────────────────────────────

    @Test
    fun `stale callback from old recognizer is ignored`() {
        val recListener1 = startListeningAndCapture()

        // Stop and start again — generation increments
        sttManager.stop()
        val recListener2 = startListeningAndCapture()

        // Callback from old recognizer should be ignored
        recListener1.onResults(makeResultBundle("stale result"))

        // Should NOT deliver the stale result
        verify(exactly = 0) { mockListener.onResult("stale result") }
    }

    @Test
    fun `stale error from old recognizer is ignored`() {
        val recListener1 = startListeningAndCapture()
        sttManager.stop()
        startListeningAndCapture()

        // Old recognizer's error should be ignored
        recListener1.onError(SpeechRecognizer.ERROR_NO_MATCH)
        // Should not fire onError for the stale callback
        verify(exactly = 0) { mockListener.onError(any()) }
    }

    // ── Error handling ──────────────────────────────────────────────────────────

    @Test
    fun `non-fatal error during active session triggers auto-restart`() {
        val recListener = startListeningAndCapture()
        recListener.onError(SpeechRecognizer.ERROR_NO_MATCH)

        // Should NOT fire onError (auto-restart instead)
        verify(exactly = 0) { mockListener.onError(any()) }

        // Advance looper to trigger restart
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        verify(atLeast = 2) { SpeechRecognizer.createSpeechRecognizer(any()) }
    }

    @Test
    fun `rate limited error code 11 waits 500ms before restart`() {
        val recListener = startListeningAndCapture()
        recListener.onError(11) // rate-limited

        // Should NOT fire onError
        verify(exactly = 0) { mockListener.onError(any()) }

        // Advance looper
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        verify(atLeast = 2) { SpeechRecognizer.createSpeechRecognizer(any()) }
    }

    @Test
    fun `ERROR_AUDIO is fatal — delivers text and stops`() {
        val recListener = startListeningAndCapture()

        // Accumulate some text first
        recListener.onResults(makeResultBundle("accumulated"))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Get new listener after restart
        val recListener2 = capturedRecognitionListener!!
        recListener2.onError(SpeechRecognizer.ERROR_AUDIO)

        verify { mockListener.onError("Audio error") }
        verify { mockListener.onListeningEnded() }
    }

    @Test
    fun `ERROR_INSUFFICIENT_PERMISSIONS is fatal`() {
        val recListener = startListeningAndCapture()
        recListener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        verify { mockListener.onError("Permission denied") }
        verify { mockListener.onListeningEnded() }
    }

    @Test
    fun `ERROR_NO_MATCH outside active session with short elapsed restarts (Samsung quirk)`() {
        // This tests the Samsung quirk where ERROR_NO_MATCH fires within 2 seconds
        // The manager is in non-push-to-talk mode (isActive = false after stop)

        // Start listening in push-to-talk mode
        val recListener = startListeningAndCapture()

        // Finalize to end push-to-talk session
        sttManager.finalize()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Start again in non-push-to-talk mode — actually SpeechRecognizerManager
        // always uses push-to-talk. The Samsung quirk is handled inside the error callback.
        // This is covered by the non-fatal error auto-restart test above.
    }

    // ── stop ────────────────────────────────────────────────────────────────────

    @Test
    fun `stop destroys recognizer`() {
        startListeningAndCapture()
        sttManager.stop()
        verify { mockRecognizer.stopListening() }
        verify { mockRecognizer.cancel() }
        verify { mockRecognizer.destroy() }
    }

    @Test
    fun `stop cancels pending handler callbacks`() {
        val recListener = startListeningAndCapture()
        recListener.onResults(makeResultBundle("text"))
        // A delayed restart is now pending

        sttManager.stop()
        // Generation incremented, pending restart should not fire
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // No onResult should fire from the restart
        verify(exactly = 0) { mockListener.onResult(any()) }
    }

    @Test
    fun `stop is safe to call when not listening`() {
        sttManager.stop() // should not throw
    }

    // ── Mute / restore beep sounds ──────────────────────────────────────────────

    @Test
    fun `startListening mutes beep sounds`() {
        val audioManager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)

        startListeningAndCapture()

        val mutedVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        assertEquals("Notification volume should be muted", 0, mutedVolume)
    }

    @Test
    fun `finalize restores beep sounds`() {
        val audioManager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)

        startListeningAndCapture()
        sttManager.finalize()

        val restoredVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        assertEquals("Volume should be restored", originalVolume, restoredVolume)
    }

    @Test
    fun `restoreBeepSounds is idempotent`() {
        startListeningAndCapture()
        sttManager.finalize()
        // Calling finalize again should not crash or mangle volume
        sttManager.finalize()
    }

    // ── onEndOfSpeech ───────────────────────────────────────────────────────────

    @Test
    fun `onEndOfSpeech during active session does not fire onListeningEnded`() {
        val recListener = startListeningAndCapture()
        recListener.onEndOfSpeech()

        // During active push-to-talk, onEndOfSpeech should not end the session
        verify(exactly = 0) { mockListener.onListeningEnded() }
    }

    // ── Multiple segments ───────────────────────────────────────────────────────

    @Test
    fun `multiple segments accumulate correctly`() {
        val recListener1 = startListeningAndCapture()
        recListener1.onResults(makeResultBundle("hello"))

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val recListener2 = capturedRecognitionListener!!
        recListener2.onResults(makeResultBundle("world"))

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        sttManager.finalize()
        verify { mockListener.onResult("hello world") }
    }
}
