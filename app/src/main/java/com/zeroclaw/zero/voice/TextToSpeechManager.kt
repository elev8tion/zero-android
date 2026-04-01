package com.zeroclaw.zero.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onDone?.invoke()
                    }
                    @Deprecated("Deprecated")
                    override fun onError(utteranceId: String?) {
                        onDone?.invoke()
                    }
                })

                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, skipping")
            onComplete?.invoke()
            return
        }
        Log.d(TAG, "Speaking ${text.length} chars: ${text.take(80)}...")
        onDone = onComplete
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zero_utterance")
        Log.d(TAG, "speak() returned: $result")
    }

    fun stop() {
        tts?.stop()
    }

    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "ZeroTTS"
    }
}
