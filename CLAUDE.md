# Zero Android — CLAUDE.md

## What This Is
Zero is a personal-use autonomous phone agent for Android. It controls the device via an Accessibility Service, exposes 46+ MCP tools over HTTP, and connects to a session proxy for AI-driven phone control.

## Project Tracker
**All tasks live in T0ggles** — board "Zero's Boards" (`3asoYa8WGR9whoNU1flF`), project key `DEMO`.

Before starting any work:
1. Use `mcp__t0ggles__list-tasks` on board `3asoYa8WGR9whoNU1flF` to see what's To-Do
2. Use `mcp__t0ggles__get-blocked-tasks` to see dependency order
3. Pick the next unblocked To-Do task by key number
4. Mark it in-progress, do the work, mark it Done when committed
5. **Never guess what's next** — always check the board

## Build Commands
```bash
# Compile check
./gradlew compileDebugKotlin

# JVM unit tests
./gradlew test

# Instrumented tests (device connected)
./gradlew connectedDebugAndroidTest

# Build debug APK
./gradlew assembleDebug

# Install on device
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Repo Structure
```
app/src/main/java/com/zeroclaw/zero/
├── ui/                  # MainActivity, SettingsActivity, PermissionManager
├── tools/               # Tool.kt, ToolRegistry.kt, DeviceTools, SystemTools, ContactsTools, etc.
├── accessibility/       # ZeroAccessibilityService, ScreenContent
├── overlay/             # OverlayService (floating orb)
├── mcp/                 # McpServer, McpServerService (JSON-RPC over HTTP)
├── agent/               # AgentLoop
├── voice/               # SpeechRecognizerManager, TextToSpeechManager
├── data/                # AppPrefs, GatewayClient, GatewayModels
├── widget/              # ZeroWidgetProvider
└── ZeroApp.kt           # Application class, tool registration
app/src/main/res/layout/ # activity_main.xml, activity_settings.xml
app/src/test/            # JVM unit tests
app/src/androidTest/     # Instrumented tests
```

## Commit Style
Imperative subject, body explains why. Example:
```
Add runtime permission request flow (DEMO-12)

Centralize all permission logic in PermissionManager...
```

## Device
Samsung Galaxy A15 (SM-A156U), Android 14, API 34
