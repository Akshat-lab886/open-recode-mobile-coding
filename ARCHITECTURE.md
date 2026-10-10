# DSH Mobile — Touch-Driven Agent Control Center

Fully visual, touch-driven Android app (Kotlin + Jetpack Compose) that runs real
AI coding agents — DeepSeek Harness, Claude Code, Codex, OpenCode — with **no
typing and no raw CLI** anywhere in the user journey.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ MainActivity (CORE ENTRY POINT, ComponentActivity)          │
│  • startForegroundService(ACTION_BOOT) on launch            │
│  • setContent { DshMobileTheme { DshMobileShell(...) } }    │
└──────────────┬──────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────┐
│ AgentRuntimeService (sticky Foreground Service, Lifecycle)  │
│  boot(): config → workspace → env(keys) → spawn →           │
│          gateway attach → port health-check → Running →     │
│          watchdog (restart w/ backoff)                      │
│  Publishes every change to AgentServiceStatusFlow           │
└──┬───────────────────────────────┬──────────────────────────┘
   │ child Process (PRoot/Node)    │ GatewayEventBus
   │ 127.0.0.1:<dynamic port>      │ (chat deltas, approvals)
┌──▼──────────────────┐  ┌─────────▼──────────────────────────┐
│ AgentWebView        │  │ ControlCenterViewModel             │
│ (System WebView)    │◄─┤ (config + status + approvals flow) │
│ + dsh-mobile-       │  │                                    │
│   shell.js injection│  │ ControlCenterSheet (keys/folder/   │
└─────────────────────┘  │ mode/agent) · ApprovalSheet        │
                         └────────────────────────────────────┘
```

## Module map

| File | Role |
|---|---|
| `DshMobileApp.kt` | App entry: owns `ApiKeyVault` + `RuntimeConfigStore` singletons |
| `MainActivity.kt` | **Core entry point**: boots service, renders `DshMobileShell` |
| `service/AgentRuntimeService.kt` | **Service orchestration**: mounts dirs, injects keys, spawns agent on loopback, health-checks, watchdog restarts, answers approvals over stdin |
| `runtime/AgentRuntime.kt` | PRoot/Node layout, free-port picker, argv + env builders. Modes enforced via `DSH_AGENT_MODE` env — never as CLI flags |
| `proxy/VisualGateway.kt` | Stream pump + approval classifier for directly-spawned CLI backends. **Not** attached to `gateway.js` (see stdin ownership below) |
| `assets/visual-gateway/gateway.js` | **CLI→visual bridge (owns the real agent's stdin/stdout)**: serves `/health`, `/events` (SSE), `/send`, `/files`, `/file` + the chat UI; classifies approval prompts and emits them as events |
| `security/ApiKeyVault.kt` | AES-256-GCM Keystore vault → silent env injection (`DEEPSEEK_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_*`) |
| `data/RuntimeConfigStore.kt` | DataStore config: workspace path, `AgentMode`, `ActiveAgent`, port |
| `ui/DshMobileShell.kt` | Visual shell: WebView + Control Center sheet + Approval sheets + boot states |
| `ui/AgentWebView.kt` | WebView container: `DshBridge` JS interface, `WebApprovalRouter`, `GatewayEventBus` fan-out |
| `ui/ControlCenterViewModel.kt` | `ControlCenterState` + `AgentServiceStatusFlow` live bridge + bootstrap/watchdog/diagnostics actions |
| `ui/ResilienceViews.kt` | `BootstrapProgressView` (progress bar + log tail), `EngineErrorCard` ([Restart Engine]), `InputAccessoryBar` (quick commands) |
| `ui/DiagnosticsSheet.kt` | Hidden diagnostics sheet (log tabs + one-tap clipboard export) |
| `bootstrap/BootstrapManager.kt` | First-boot script executor: parses `>>>STEP/PROGRESS/LOG/ERROR/DONE` into progress state |
| `watchdog/EngineWatchdog.kt` | 5s loopback `/health` polling, Doze/crash classification, diagnostic trace capture |
| `diagnostics/DiagnosticsStore.kt` | Log tailing (agent + harness.log + bootstrap) with secret redaction + clipboard export |
| `assets/dsh-mobile-shell.js` | **CSS/JS injection**: drawers, Chat/Files tabs, pinned composer, hides xterm, forwards DOM prompts to `DshBridge` |
| `assets/visual-gateway/ui.html` | Chat UI served by `gateway.js`. Opens SSE, posts to `/send`, forwards approvals to the native sheet via `DshBridge.postApprovalRequest`, answers via `__gatewayResolve` |
| `assets/bootstrap.sh` | **First-boot script**: arm64 check → rootfs unpack → pkg update → build tools → Node verify → `npm i -g @deepseek-ai/dsh` + plugins |

## Stdin ownership (important)

Each CLI agent's stdin has **exactly one owner**, or approvals deadlock:

| Backend | Spawned process | Who owns the CLI's stdin | Approval path |
|---|---|---|---|
| DeepSeek Harness | `dsh web` (native HTTP) | nobody — it serves its own UI | in-page DOM scan → `DshBridge` → native sheet |
| OpenCode | `opencode serve` | nobody — same as above | same as above |
| Claude Code / Codex | `node gateway.js -- <cli>` | **`gateway.js`** | gateway SSE → `DshBridge.postApprovalRequest` → native sheet → `__gatewayResolve` → `POST /send` |

`VisualGateway` must therefore **not** attach when the child is `gateway.js` —
doing so would capture gateway.js's own pipes (the wrong process) and write
approval tokens to a stdin nothing reads. `AgentRuntimeService` keeps
`gateway = null` for gateway backends and this is load-bearing.

The same approval patterns live in **two** places (`VisualGateway.kt` and
`gateway.js`); `GatewayJsContractTest` extracts them from the JS source and
fails if they drift.

## Key flows

- **First boot**: Activity launches → `BootstrapManager.ensureBootstrapped()`
  runs `assets/bootstrap.sh` (arch check → rootfs unpack → pkg update →
  build tools → Node verify → `npm i -g @deepseek-ai/dsh` + plugins) with
  `>>>STEP/PROGRESS/LOG/ERROR/DONE` markers parsed into
  `BootstrapProgressView` (progress bar + step + scrolling log). The service
  boot gates on the same marker file, so early `ACTION_BOOT` waits instead of
  crash-looping. Retry re-runs idempotently.
- **Boot**: Activity → `ACTION_BOOT` → service reads config+vault → prepares
  dirs → `ProcessBuilder` spawns agent → gateway attach (CLI agents) →
  loopback health-check → `Running(url)` → WebView loads URL + injects shell.
  `EngineWatchdog.start(port)` arms 5s `/health` pings from here on.
- **Crash recovery**: 3 consecutive probe failures (~15s) → `Unhealthy` with
  friendly reason + captured log tail → `EngineErrorCard` with
  [Restart Engine] (re-issues `ACTION_RESTART`, workspace never wiped) and a
  battery-exemption shortcut when Doze timeouts are detected.
- **Keys**: Compose form → `ApiKeyVault` (Keystore) → env vars on next
  `ACTION_RESTART`. Keys never enter the WebView or logs.
- **Workspace**: SAF picker → persisted URI perm → staged absolute path →
  `configStore` → restart passes it as `/workspace` + `DSH_WORKSPACE`.
- **Modes**: radio buttons → `DSH_AGENT_MODE` env + gateway approval strictness.
- **Approvals**: gateway regex / DOM scan → `ApprovalRequest` → native sheet
  ([Deny Execution] / [Approve Action]) → stdin `no`/`yes` (gateway) or
  `__dshResolveApproval(id, …)` (WebView).
- **Accessory bar**: `WindowInsets.ime` visibility → `InputAccessoryBar`
  (`/help`, `/stop-generation`, `/clear-context`, `/reindex-workspace` +
  workspace-path chip) → `CommandDispatcher` (web composers) or gateway
  stdin (Claude/Codex), never both (no echo loops).
- **Diagnostics**: 5-tap status dot → `DiagnosticsSheet` tails
  `dsh-logs/<agent>.log` + `dsh-runtime/.dsh/logs/harness.log` +
  `bootstrap.log` (2s poll, secrets redacted) → one-tap clipboard export.

## Build

```bash
# Requires JDK 17 + Android SDK (compileSdk 34, build-tools 36.0.0)
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 24 JVM tests
```

Gradle is pinned to **8.7** via `gradle/wrapper/` — AGP 8.5.2 predates
Gradle 9 and is incompatible with it.

Sandboxed/CI note: if `~/.gradle` or `~/.android` is not writable, point both
at the workspace:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ANDROID_USER_HOME=$PWD/.android-home ./gradlew ...
```

## Runtime payload — NOT yet bundled

The APK builds and installs, but **the agent cannot boot on a device yet**
because these are absent from `app/src/main/assets/`:

| Missing artifact | Expected location | Effect if absent |
|---|---|---|
| Alpine/Ubuntu rootfs | `dsh-runtime/rootfs.tar.gz` | `bootstrap.sh` fails with "Reinstall the app to restore bundled assets" |
| PRoot binary (arm64) | `dsh-runtime/proot` | `prootWrap()` silently runs natively; rootfs paths won't resolve |
| Node 20 for the device | `dsh-runtime/node/bin/node` | falls back to a system `node` on PATH, else fatal |
| Agent CLIs | `dsh-runtime/agents/` | `npm i -g @deepseek-ai/dsh` runs on device and needs network |

This is deliberate for now: the first-boot UX degrades to a clear error card
rather than a crash. Decide between bundling (large APK) and downloading on
first run before shipping.

## Unverified external contracts

These are assumed and have **not** been confirmed against the real tools:

1. **`dsh web` flags** — `AgentRuntime.buildCommand()` passes
   `--host/--port/--workspace`. Not verified against the real CLI.
2. **`/health` on DSH web** — `EngineWatchdog` polls `/health` then falls
   back to `/`. `gateway.js` serves `/health`; DSH web may not.
3. **`~/.dsh/logs/harness.log` location** — `DiagnosticsStore` probes the
   runtime root then the legacy home path.

Each is a one-line fix once confirmed, but shipping without checking means
the watchdog may flap or the agent may reject unknown flags.
