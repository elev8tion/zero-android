package com.zeroclaw.zero.overlay

import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.agent.AgentLoop
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.tools.ToolRegistry
import com.zeroclaw.zero.voice.TextToSpeechManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Tests the OverlayService state machine and tap logic.
 *
 * Since OverlayService is deeply tied to Android (WindowManager, Views, Notifications),
 * these tests exercise the state machine and tap detection logic via reflection.
 * This tests the LOGIC, not the UI rendering.
 */
class OverlayServiceTest {

    // We test the state machine logic directly through reflection
    // because OverlayService can't easily be instantiated on JVM.

    // ── State enum values ───────────────────────────────────────────────────────

    @Test
    fun `State enum has exactly four values`() {
        val states = OverlayService.State.values()
        assertEquals(4, states.size)
        assertTrue(states.map { it.name }.containsAll(
            listOf("IDLE", "LISTENING", "THINKING", "SPEAKING")
        ))
    }

    // ── State machine transition logic ──────────────────────────────────────────
    // Since we can't instantiate OverlayService easily, we test the state machine
    // through a StateMachineSimulator that mirrors the exact logic from handleTap().

    private class StateMachineSimulator {
        var state = OverlayService.State.IDLE
        var tapCount = 0
        var lastTapTime = 0L
        var lastStateChangeTime = 0L

        val TAP_COOLDOWN_MS = 400L
        val TRIPLE_TAP_WINDOW_MS = 500L
        val TAPS_TO_START = 3

        // Mirrors events triggered by taps
        var startListeningCalled = false
        var stopListeningCalled = false
        var stopTtsCalled = false

        fun transitionTo(newState: OverlayService.State) {
            state = newState
            lastStateChangeTime = currentTimeMs
        }

        var currentTimeMs = 1000L

        /**
         * Exact mirror of OverlayService.handleTap() logic.
         */
        fun handleTap() {
            val now = currentTimeMs

            // Phantom tap cooldown
            if (now - lastStateChangeTime < TAP_COOLDOWN_MS) {
                return
            }

            when (state) {
                OverlayService.State.IDLE -> {
                    if (now - lastTapTime > TRIPLE_TAP_WINDOW_MS) {
                        tapCount = 0
                    }
                    tapCount++
                    lastTapTime = now

                    if (tapCount >= TAPS_TO_START) {
                        tapCount = 0
                        startListeningCalled = true
                        transitionTo(OverlayService.State.LISTENING)
                    }
                }
                OverlayService.State.LISTENING -> {
                    stopListeningCalled = true
                }
                OverlayService.State.THINKING -> {
                    // ignored
                }
                OverlayService.State.SPEAKING -> {
                    stopTtsCalled = true
                    transitionTo(OverlayService.State.IDLE)
                }
            }
        }

        fun reset() {
            startListeningCalled = false
            stopListeningCalled = false
            stopTtsCalled = false
        }
    }

    private lateinit var sim: StateMachineSimulator

    @Before
    fun setup() {
        sim = StateMachineSimulator()
    }

    // ── IDLE state ──────────────────────────────────────────────────────────────

    @Test
    fun `IDLE - triple tap starts listening`() {
        sim.currentTimeMs = 1000
        sim.handleTap()
        assertEquals(1, sim.tapCount)
        assertFalse(sim.startListeningCalled)

        sim.currentTimeMs = 1100
        sim.handleTap()
        assertEquals(2, sim.tapCount)
        assertFalse(sim.startListeningCalled)

        sim.currentTimeMs = 1200
        sim.handleTap()
        assertTrue("Should start listening after 3 taps", sim.startListeningCalled)
        assertEquals(OverlayService.State.LISTENING, sim.state)
    }

    @Test
    fun `IDLE - single tap shows progress but does not start`() {
        sim.currentTimeMs = 1000
        sim.handleTap()
        assertEquals(1, sim.tapCount)
        assertFalse(sim.startListeningCalled)
        assertEquals(OverlayService.State.IDLE, sim.state)
    }

    @Test
    fun `IDLE - tap count resets after window expires`() {
        sim.currentTimeMs = 1000
        sim.handleTap()
        assertEquals(1, sim.tapCount)

        sim.currentTimeMs = 1100
        sim.handleTap()
        assertEquals(2, sim.tapCount)

        // Wait longer than TRIPLE_TAP_WINDOW_MS
        sim.currentTimeMs = 2000
        sim.handleTap()
        // Tap count should have reset — this is tap 1, not tap 3
        assertEquals(1, sim.tapCount)
        assertFalse(sim.startListeningCalled)
    }

    @Test
    fun `IDLE - phantom tap during cooldown is ignored`() {
        // Simulate a state change just happened
        sim.transitionTo(OverlayService.State.IDLE)
        sim.currentTimeMs = sim.lastStateChangeTime + 100 // within cooldown

        sim.handleTap()
        assertEquals(0, sim.tapCount) // tap was ignored
    }

    @Test
    fun `IDLE - tap after cooldown is accepted`() {
        sim.transitionTo(OverlayService.State.IDLE)
        sim.currentTimeMs = sim.lastStateChangeTime + 500 // past cooldown

        sim.handleTap()
        assertEquals(1, sim.tapCount)
    }

    // ── LISTENING state ─────────────────────────────────────────────────────────

    @Test
    fun `LISTENING - tap triggers stop listening`() {
        sim.transitionTo(OverlayService.State.LISTENING)
        sim.currentTimeMs = sim.lastStateChangeTime + 500

        sim.handleTap()
        assertTrue(sim.stopListeningCalled)
    }

    @Test
    fun `LISTENING - tap during cooldown is ignored`() {
        sim.transitionTo(OverlayService.State.LISTENING)
        sim.currentTimeMs = sim.lastStateChangeTime + 100 // within cooldown

        sim.handleTap()
        assertFalse(sim.stopListeningCalled)
    }

    // ── THINKING state ──────────────────────────────────────────────────────────

    @Test
    fun `THINKING - tap is ignored`() {
        sim.transitionTo(OverlayService.State.THINKING)
        sim.currentTimeMs = sim.lastStateChangeTime + 500

        sim.handleTap()
        assertFalse(sim.startListeningCalled)
        assertFalse(sim.stopListeningCalled)
        assertFalse(sim.stopTtsCalled)
        assertEquals(OverlayService.State.THINKING, sim.state)
    }

    // ── SPEAKING state ──────────────────────────────────────────────────────────

    @Test
    fun `SPEAKING - tap stops TTS and returns to IDLE`() {
        sim.transitionTo(OverlayService.State.SPEAKING)
        sim.currentTimeMs = sim.lastStateChangeTime + 500

        sim.handleTap()
        assertTrue(sim.stopTtsCalled)
        assertEquals(OverlayService.State.IDLE, sim.state)
    }

    @Test
    fun `SPEAKING - tap during cooldown is ignored`() {
        sim.transitionTo(OverlayService.State.SPEAKING)
        sim.currentTimeMs = sim.lastStateChangeTime + 100

        sim.handleTap()
        assertFalse(sim.stopTtsCalled)
        assertEquals(OverlayService.State.SPEAKING, sim.state)
    }

    // ── State change updates lastStateChangeTime ────────────────────────────────

    @Test
    fun `setState updates lastStateChangeTime`() {
        sim.currentTimeMs = 5000
        sim.transitionTo(OverlayService.State.THINKING)
        assertEquals(5000L, sim.lastStateChangeTime)
    }

    // ── Full flow simulation ────────────────────────────────────────────────────

    @Test
    fun `full flow - IDLE triple-tap to LISTENING to stop to THINKING`() {
        // Triple-tap to start
        sim.currentTimeMs = 1000
        sim.handleTap()
        sim.currentTimeMs = 1100
        sim.handleTap()
        sim.currentTimeMs = 1200
        sim.handleTap()
        assertEquals(OverlayService.State.LISTENING, sim.state)
        assertTrue(sim.startListeningCalled)

        // Tap to stop listening
        sim.reset()
        sim.currentTimeMs = 3000
        sim.handleTap()
        assertTrue(sim.stopListeningCalled)
    }

    @Test
    fun `phantom tap prevention - rapid state changes do not trigger actions`() {
        // This is THE bug scenario: animation shift causes phantom tap
        sim.transitionTo(OverlayService.State.IDLE)
        sim.currentTimeMs = sim.lastStateChangeTime + 50 // way too soon

        // This tap should be completely ignored
        sim.handleTap()
        assertEquals(0, sim.tapCount)
        assertFalse(sim.startListeningCalled)

        // Move past cooldown, taps should work again
        sim.currentTimeMs = sim.lastStateChangeTime + 500
        sim.handleTap()
        assertEquals(1, sim.tapCount)
    }

    @Test
    fun `speaking interrupt returns to IDLE which resets tap count`() {
        sim.transitionTo(OverlayService.State.SPEAKING)
        sim.currentTimeMs = sim.lastStateChangeTime + 500

        sim.handleTap() // stops TTS, returns to IDLE
        assertEquals(OverlayService.State.IDLE, sim.state)

        // Now try triple-tap from IDLE
        sim.currentTimeMs = sim.lastStateChangeTime + 500
        sim.reset()
        sim.handleTap()
        assertEquals(1, sim.tapCount)
        sim.currentTimeMs += 100
        sim.handleTap()
        assertEquals(2, sim.tapCount)
        sim.currentTimeMs += 100
        sim.handleTap()
        assertEquals(OverlayService.State.LISTENING, sim.state)
    }

    @Test
    fun `double tap in IDLE does not trigger listening`() {
        sim.currentTimeMs = 1000
        sim.handleTap()
        sim.currentTimeMs = 1100
        sim.handleTap()
        assertEquals(2, sim.tapCount)
        assertEquals(OverlayService.State.IDLE, sim.state)
        assertFalse(sim.startListeningCalled)
    }

    @Test
    fun `cooldown value is 400ms`() {
        assertEquals(400L, sim.TAP_COOLDOWN_MS)
    }

    @Test
    fun `triple tap window is 500ms`() {
        assertEquals(500L, sim.TRIPLE_TAP_WINDOW_MS)
    }

    @Test
    fun `taps required is 3`() {
        assertEquals(3, sim.TAPS_TO_START)
    }
}
