package com.zeroclaw.zero.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var startTime: Long = 0
    private val accumulatedText = StringBuilder()
    private var isActive = false      // true while push-to-talk session is open
    private var isFirstStart = false  // true only for the initial start, not auto-restarts
    private var generation = 0        // incremented on each new recognizer; stale callbacks are rejected
    private val handler = Handler(Looper.getMainLooper())

    interface Listener {
        fun onResult(text: String)
        fun onPartial(text: String)
        fun onListeningStarted()
        fun onListeningEnded()
        fun onError(message: String)
    }

    fun setListener(l: Listener) {
        listener = l
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener?.onError("Speech recognition unavailable")
            return
        }

        stop()

        if (!isActive) {
            accumulatedText.clear()
            isActive = true
            isFirstStart = true
        }

        val myGen = ++generation  // capture generation for this recognizer's callbacks

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (myGen != generation) return
                    if (!isActive) {
                        listener?.onListeningEnded()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (myGen != generation) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: return
                    val display = if (accumulatedText.isNotEmpty()) {
                        "${accumulatedText} $partial"
                    } else {
                        partial
                    }
                    listener?.onPartial(display)
                }

                override fun onResults(results: Bundle?) {
                    if (myGen != generation) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        if (accumulatedText.isNotEmpty()) accumulatedText.append(" ")
                        accumulatedText.append(text)
                        listener?.onPartial(accumulatedText.toString())
                    }
                    if (isActive) {
                        Log.d(TAG, "Segment done, auto-restarting (accumulated=${accumulatedText.length} chars)")
                        restartDelayed(300)
                        return
                    }
                    // Not in push-to-talk — legacy behavior
                    if (accumulatedText.isNotBlank()) {
                        listener?.onResult(accumulatedText.toString().trim())
                    } else {
                        listener?.onError("No speech detected")
                    }
                    accumulatedText.clear()
                    listener?.onListeningEnded()
                }

                override fun onError(error: Int) {
                    if (myGen != generation) {
                        Log.d(TAG, "Ignoring stale error (code=$error, gen=$myGen, current=$generation)")
                        return
                    }

                    val elapsed = System.currentTimeMillis() - startTime

                    if (isActive) {
                        val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                                error == SpeechRecognizer.ERROR_AUDIO
                        if (!fatal) {
                            // Rate-limited (code 11) or recognizer busy — wait longer before retry
                            val delay = if (error == 11 || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                Log.d(TAG, "Rate-limited (code=$error), waiting 500ms before restart")
                                500L
                            } else {
                                Log.d(TAG, "Auto-restart in push-to-talk (code=$error, elapsed=${elapsed}ms)")
                                300L
                            }
                            restartDelayed(delay)
                            return
                        }
                    }

                    // Samsung quirk: ERROR_NO_MATCH fires early even outside push-to-talk
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && elapsed < 2000) {
                        Log.d(TAG, "Quick ERROR_NO_MATCH (${elapsed}ms), restarting")
                        restartDelayed(300)
                        return
                    }

                    // Fatal or non-push-to-talk error — deliver what we have and stop
                    isActive = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                        else -> "Recognition error ($error)"
                    }
                    Log.w(TAG, "STT error: $msg (code=$error, elapsed=${elapsed}ms)")
                    val finalText = accumulatedText.toString().trim()
                    accumulatedText.clear()
                    if (finalText.isNotBlank()) {
                        listener?.onResult(finalText)
                    }
                    listener?.onError(msg)
                    listener?.onListeningEnded()
                }

                @Deprecated("Deprecated")
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Extend silence thresholds so recognizer doesn't cut off mid-thought
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 3000L)
        }

        startTime = System.currentTimeMillis()
        recognizer?.startListening(intent)
        if (isFirstStart) {
            isFirstStart = false
            listener?.onListeningStarted()
        }
    }

    /** Delayed restart — gives Android time to release the mic between sessions. */
    private fun restartDelayed(delayMs: Long) {
        stop()
        val restartGen = generation  // capture so we can check if finalize() was called during the delay
        handler.postDelayed({
            if (isActive && generation == restartGen) {
                startListening()
            }
        }, delayMs)
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)  // cancel any pending restarts
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recognizer: ${e.message}")
        }
        recognizer = null
    }

    /** User taps mic to stop — deliver accumulated text and end session. */
    fun finalize() {
        isActive = false
        val finalText = accumulatedText.toString().trim()
        accumulatedText.clear()
        stop()  // generation++ + cancel pending restarts
        if (finalText.isNotBlank()) {
            listener?.onResult(finalText)
        }
        listener?.onListeningEnded()
    }

    companion object {
        private const val TAG = "ZeroSTT"
    }
}
