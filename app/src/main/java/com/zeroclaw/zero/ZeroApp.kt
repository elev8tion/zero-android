package com.zeroclaw.zero

import android.app.Application
import android.util.Log
import com.zeroclaw.zero.agent.AgentLoop
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.ErrorDatabase
import com.zeroclaw.zero.data.T0gglesClient
import com.zeroclaw.zero.tools.*
import com.zeroclaw.zero.tools.ToolRegistry
import com.zeroclaw.zero.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZeroApp : Application() {

    companion object {
        private const val TAG = "ZeroApp"
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
    lateinit var t0gglesClient: T0gglesClient
        private set
    lateinit var errorDatabase: ErrorDatabase
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance     = this
        prefs        = AppPrefs(this)
        ttsManager   = TextToSpeechManager(this)
        toolRegistry = buildToolRegistry()
        agentLoop    = AgentLoop()

        // Initialize T0ggles client and error database
        t0gglesClient = T0gglesClient(prefs)
        errorDatabase = ErrorDatabase(t0gglesClient, prefs, appScope)
        toolRegistry.errorDatabase = errorDatabase

        // Register the query_error_log tool (local, no T0ggles needed)
        toolRegistry.register(QueryErrorLogTool(errorDatabase))

        // Register workflow template tools
        toolRegistry.register(ListWorkflowsTool())
        toolRegistry.register(RunWorkflowTool(t0gglesClient, prefs))

        // Discover and register T0ggles workflow tools in background
        appScope.launch { registerT0gglesTools() }
    }

    override fun onTerminate() {
        agentLoop.cancel()
        ttsManager.shutdown()
        super.onTerminate()
    }

    /**
     * Discover all T0ggles MCP tools and register them as proxy tools.
     * Called on a background thread at startup — failures are logged, not fatal.
     */
    suspend fun registerT0gglesTools() {
        try {
            t0gglesClient.initialize()
            val remoteDefs = t0gglesClient.discoverTools()
            for (def in remoteDefs) {
                val proxy = T0gglesProxyTool.fromRemoteDef(t0gglesClient, def, prefs)
                toolRegistry.register(proxy)
            }
            Log.i(TAG, "Registered ${remoteDefs.size} T0ggles workflow tools")
        } catch (e: Exception) {
            Log.w(TAG, "T0ggles discovery failed: ${e.message} — workflow tools unavailable")
        }
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
            register(GetBluetoothStatusTool(this@ZeroApp))
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
