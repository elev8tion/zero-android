package com.zeroclaw.zero.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zeroclaw.zero.R
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.agent.AgentLoop
import com.zeroclaw.zero.ui.MainActivity
import com.zeroclaw.zero.voice.SpeechRecognizerManager

private const val TAG          = "OverlayService"
private const val NOTIF_ID     = 42
private const val CHANNEL_ID   = "zero_overlay"
private const val DRAG_DP      = 8f   // pixels moved before treating touch as drag
private const val TRIPLE_TAP_WINDOW_MS = 500L  // max time between taps for triple-tap
private const val TAPS_TO_START = 3            // triple-tap to start listening

class OverlayService : Service() {

    // ── State ─────────────────────────────────────────────────────────────────

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private var state = State.IDLE

    // ── Views & system services ────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var orbView: View
    private lateinit var orbStatus: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())

    // ── Triple-tap detection ────────────────────────────────────────────────────

    private var tapCount = 0
    private var lastTapTime = 0L
    private var lastStateChangeTime = 0L           // debounce phantom taps from animation shifts
    private val TAP_COOLDOWN_MS = 400L             // ignore taps for this long after state change

    // ── Voice managers ────────────────────────────────────────────────────────

    private lateinit var stt: SpeechRecognizerManager

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inflate bubble
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_zero, null)
        orbView     = overlayView.findViewById(R.id.orbView)
        orbStatus   = overlayView.findViewById(R.id.orbStatus)

        // WindowManager params — TYPE_APPLICATION_OVERLAY floats above all apps
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        windowManager.addView(overlayView, layoutParams)

        setupTouchHandler()

        // STT — must be created on main thread
        stt = SpeechRecognizerManager(this)
        stt.setListener(sttListener)

        // Wire AgentLoop callbacks
        ZeroApp.instance.agentLoop.listener = agentListener

        setState(State.IDLE)
        Log.d(TAG, "Overlay created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        ZeroApp.instance.agentLoop.listener = null
        ZeroApp.instance.agentLoop.cancel()
        ZeroApp.instance.ttsManager.stop()
        stt.stop()
        runCatching { windowManager.removeView(overlayView) }
        Log.d(TAG, "Overlay destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Touch: drag + tap ─────────────────────────────────────────────────────

    private fun setupTouchHandler() {
        val dragThresholdPx = DRAG_DP * resources.displayMetrics.density
        var downRawX = 0f; var downRawY = 0f
        var startParamX = 0; var startParamY = 0
        var isDragging = false

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX    = event.rawX
                    downRawY    = event.rawY
                    startParamX = layoutParams.x
                    startParamY = layoutParams.y
                    isDragging  = false
                    false   // don't consume — allow click to propagate if no drag
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && (Math.abs(dx) > dragThresholdPx || Math.abs(dy) > dragThresholdPx)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = startParamX + dx.toInt()
                        layoutParams.y = startParamY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, layoutParams)
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) handleTap()
                    isDragging = false
                    false
                }
                else -> false
            }
        }
    }

    // ── Tap logic — triple-tap to start, single tap to finish ──────────────────

    private fun handleTap() {
        // Ignore phantom taps caused by animation/layout shifts
        val now = System.currentTimeMillis()
        if (now - lastStateChangeTime < TAP_COOLDOWN_MS) {
            Log.d(TAG, "Ignoring tap — too soon after state change (${now - lastStateChangeTime}ms)")
            return
        }

        when (state) {
            State.IDLE -> {
                if (now - lastTapTime > TRIPLE_TAP_WINDOW_MS) {
                    tapCount = 0  // reset — too slow between taps
                }
                tapCount++
                lastTapTime = now

                if (tapCount < TAPS_TO_START) {
                    // Show tap progress on orb
                    showStatus("$tapCount / $TAPS_TO_START")
                    // Auto-reset if user doesn't finish triple-tap in time
                    handler.removeCallbacksAndMessages("tap_reset")
                    handler.postDelayed({
                        if (state == State.IDLE) {
                            tapCount = 0
                            showStatus("")
                        }
                    }, TRIPLE_TAP_WINDOW_MS + 100)
                } else {
                    // Triple-tap reached — start listening
                    tapCount = 0
                    showStatus("")
                    startListening()
                }
            }
            State.LISTENING -> {
                // Single tap while listening — done talking, send it
                stopListening()
            }
            State.THINKING  -> { /* let it finish */ }
            State.SPEAKING  -> {
                ZeroApp.instance.ttsManager.stop()
                setState(State.IDLE)
            }
        }
    }

    private fun startListening() {
        setState(State.LISTENING)
        stt.startListening()
    }

    private fun stopListening() {
        stt.finalize()   // delivers accumulated text via onResult
    }

    // ── STT callbacks ─────────────────────────────────────────────────────────

    private val sttListener = object : SpeechRecognizerManager.Listener {
        override fun onListeningStarted() { setState(State.LISTENING) }
        override fun onListeningEnded()   {}

        override fun onPartial(text: String) {
            showStatus(text.take(60))
        }

        override fun onResult(text: String) {
            showStatus("")
            setState(State.THINKING)
            ZeroApp.instance.agentLoop.process(text)
        }

        override fun onError(message: String) {
            Log.w(TAG, "STT error: $message")
            setState(State.IDLE)
            showStatus("")
        }
    }

    // ── AgentLoop callbacks ───────────────────────────────────────────────────

    private val agentListener = object : AgentLoop.Listener {
        override fun onThinking() {
            setState(State.THINKING)
            showStatus("")
        }

        override fun onToolCall(name: String) {
            val label = name.replace('_', ' ')
            showStatus(label)
        }

        override fun onResponse(text: String) {
            setState(State.SPEAKING)
            showStatus("")
            ZeroApp.instance.ttsManager.speak(text) {
                setState(State.IDLE)
            }
        }

        override fun onError(message: String) {
            Log.w(TAG, "Agent error: $message")
            setState(State.IDLE)
            showStatus("Error")
            handler.postDelayed({ showStatus("") }, 2000)
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setState(newState: State) {
        state = newState
        lastStateChangeTime = System.currentTimeMillis()
        handler.post {
            orbView.clearAnimation()
            when (newState) {
                State.IDLE -> {
                    orbView.setBackgroundResource(R.drawable.bg_orb_idle)
                    orbView.alpha = 0.85f
                }
                State.LISTENING -> {
                    orbView.setBackgroundResource(R.drawable.bg_orb_listening)
                    orbView.alpha = 1.0f
                    orbView.startAnimation(
                        AnimationUtils.loadAnimation(this@OverlayService, R.anim.pulse)
                    )
                }
                State.THINKING -> {
                    orbView.setBackgroundResource(R.drawable.bg_orb_thinking)
                    orbView.alpha = 1.0f
                    orbView.startAnimation(
                        AnimationUtils.loadAnimation(this@OverlayService, R.anim.spin_slow)
                    )
                }
                State.SPEAKING -> {
                    orbView.setBackgroundResource(R.drawable.bg_orb_speaking)
                    orbView.alpha = 1.0f
                    orbView.startAnimation(
                        AnimationUtils.loadAnimation(this@OverlayService, R.anim.pulse)
                    )
                }
            }
        }
    }

    private fun showStatus(text: String) {
        handler.post {
            if (text.isBlank()) {
                orbStatus.visibility = View.GONE
                orbStatus.text = ""
            } else {
                orbStatus.text       = text
                orbStatus.visibility = View.VISIBLE
            }
        }
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Zero Overlay",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Zero floating assistant"
                    setShowBadge(false)
                }
            )
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zero")
            .setContentText("Triple-tap the orb to talk")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
