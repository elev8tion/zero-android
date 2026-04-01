package com.zeroclaw.zero.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent app preferences.
 *
 * Gateway/auth are gone. We now store:
 *  - proxyUrl  : the claude-session-proxy Ollama endpoint on the Mac
 *  - lastResponse: last assistant text, used by the home-screen widget
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zero_prefs", Context.MODE_PRIVATE)

    /** Full URL of the session proxy (http://MAC_IP:11435). */
    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY_URL, DEFAULT_PROXY_URL) ?: DEFAULT_PROXY_URL
        set(value) = prefs.edit().putString(KEY_PROXY_URL, value).apply()

    /** Last assistant response — shown in the widget. */
    var lastResponse: String?
        get() = prefs.getString(KEY_LAST_RESPONSE, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESPONSE, value).apply()

    companion object {
        private const val KEY_PROXY_URL     = "proxy_url"
        private const val KEY_LAST_RESPONSE = "last_response"
        const val DEFAULT_PROXY_URL = "http://127.0.0.1:11435"
    }
}
