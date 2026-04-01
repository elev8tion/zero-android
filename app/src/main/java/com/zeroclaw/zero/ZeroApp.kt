package com.zeroclaw.zero

import android.app.Application
import com.zeroclaw.zero.agent.AgentLoop
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.tools.*
import com.zeroclaw.zero.tools.ToolRegistry
import com.zeroclaw.zero.voice.TextToSpeechManager

class ZeroApp : Application() {

    companion object {
        @Volatile lateinit var instance: ZeroApp
            private set
    }

    lateinit var prefs: AppPrefs
        private set
    lateinit var ttsManager: TextToSpeechManager
        private set
    lateinit var toolRegistry: ToolRegistry
        private set
    lateinit var agentLoop: AgentLoop
        private set

    override fun onCreate() {
        super.onCreate()
        instance    = this
        prefs       = AppPrefs(this)
        ttsManager  = TextToSpeechManager(this)
        toolRegistry = buildToolRegistry()
        agentLoop   = AgentLoop()
    }

    override fun onTerminate() {
        agentLoop.cancel()
        ttsManager.shutdown()
        super.onTerminate()
    }

    private fun buildToolRegistry(): ToolRegistry {
        return ToolRegistry(this).apply {

            // ── Gestures ──────────────────────────────────────────────────────
            register(TapTool())
            register(LongTapScreenTool())
            register(SwipeTool())
            register(ScrollTool())
            register(TypeTextTool())
            register(SubmitTextTool())
            register(ClearTextTool())
            register(FindAndClickTool())
            register(FindAndClickByIdTool())

            // ── Navigation ────────────────────────────────────────────────────
            register(PressBackTool())
            register(PressHomeTool())
            register(PressRecentsTool())
            register(ShowAllAppsTool())
            register(FindEditableAndTypeTool())

            // ── Screen reading ─────────────────────────────────────────────────
            register(GetScreenStateTool())
            register(GetScreenContentTool())
            register(GetScreenJsonTool())
            register(GetDeviceStatusTool())
            register(ClickNodeTool())
            register(LongClickNodeTool())
            register(FindElementTool())
            register(WaitForElementTool())
            register(AwaitScreenChangeTool())
            register(TakeScreenshotTool())

            // ── App control ────────────────────────────────────────────────────
            register(LaunchAppTool(this@ZeroApp))
            register(FindAppTool(this@ZeroApp))
            register(ListAppsTool(this@ZeroApp))
            register(GetCurrentAppTool())

            // ── System settings ────────────────────────────────────────────────
            register(GetBrightnessTool(this@ZeroApp))
            register(SetBrightnessTool(this@ZeroApp))
            register(GetVolumeTool(this@ZeroApp))
            register(SetVolumeTool(this@ZeroApp))
            register(GetWifiStateTool(this@ZeroApp))
            register(GetScreenTimeoutTool(this@ZeroApp))
            register(SetScreenTimeoutTool(this@ZeroApp))

            // ── Device hardware ─────────────────────────────────────────────────
            register(GetAutoRotateTool(this@ZeroApp))
            register(SetAutoRotateTool(this@ZeroApp))
            register(GetBluetoothStatusTool())
            register(SetFlashlightTool(this@ZeroApp))
            register(SetRingerModeTool(this@ZeroApp))
            register(GetDndStatusTool(this@ZeroApp))
            register(SetDndModeTool(this@ZeroApp))
            register(GetLocationTool(this@ZeroApp))

            // ── Contacts & communication ────────────────────────────────────────
            register(ListContactsTool(this@ZeroApp))
            register(SendSmsTool(this@ZeroApp))
            register(MakeCallTool(this@ZeroApp))
        }
    }
}
