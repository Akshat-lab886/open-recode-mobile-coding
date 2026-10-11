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
│          tee stdout (AgentLaunch: log + entry-URL capture)  │
│          → port check → Running(url) → watchdog (restart)   │
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

DSH's UI is **not** served at the bare origin: `dsh web` sits behind a
browser-trust fence and prints its real entry point (with a session token) on
stdout. See "Key flows → DSH entry URL".

## Module map

| File | Role |
|---|---|
| `DshMobileApp.kt` | App entry: owns `ApiKeyVault` + `RuntimeConfigStore` singletons |
| `MainActivity.kt` | **Core entry point**: boots service, renders `DshMobileShell` |
| `service/AgentRuntimeService.kt` | **Service orchestration**: mounts dirs, injects keys, spawns agent on loopback, health-checks, watchdog restarts, answers approvals over stdin |
| `service/AgentLaunch.kt` | **Launch-URL capture**: tees the child's stdout/stderr to the agent log *and* scans it for the `dsh web: <url>` banner. Refuses non-loopback URLs so a hostile child cannot redirect the WebView off-device. Redacts `token=…` |
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
| `watchdog/EngineWatchdog.kt` | 5s liveness polling (TCP first), Doze/crash classification, diagnostic trace capture |
| `watchdog/EngineHealthProbe.kt` | One probe: TCP connect (primary) → `GET /health` then `GET /` (HTTP fallback only) |
| `watchdog/Liveness.kt` | **Pure** status/exception → healthy-or-not classifier. No socket, no Android, so the contract is unit-testable |
| `diagnostics/DiagnosticsStore.kt` | Log tailing (agent + harness.log + bootstrap) with secret redaction + clipboard export |
| `assets/dsh-mobile-shell.js` | **CSS/JS injection**: drawers, Chat/Files tabs, pinned composer, hides xterm, forwards DOM prompts to `DshBridge` |
| `assets/visual-gateway/ui.html` | Chat UI served by `gateway.js`. Opens SSE, posts to `/send`, forwards approvals to the native sheet via `DshBridge.postApprovalRequest`, answers via `__gatewayResolve` |
| `assets/bootstrap.sh` | **First-boot script**: arm64 check → rootfs unpack → pkg update → build tools → Node verify → `npm i -g @deepseek-ai/dsh` + plugins |

## Stdin ownership (important)

Each CLI agent's stdin has **exactly one owner**, or approvals deadlock:

| Backend | Spawned process | Who owns the CLI's stdin | Approval path | Liveness probe |
|---|---|---|---|---|
| DeepSeek Harness | `dsh --profile web` (native HTTP) | nobody — it serves its own UI | in-page DOM scan → `DshBridge` → native sheet | **TCP connect only.** No `/health` route exists (404), and `/` answers 401 behind the browser-trust fence — an HTTP-status check would declare a healthy agent dead and restart-loop it |
| OpenCode | `opencode serve` | nobody — same as above | same as above | `opencode serve`'s own `/health`; the probe still short-circuits on TCP |
| Claude Code / Codex | `node gateway.js -- <cli>` | **`gateway.js`** | gateway SSE → `DshBridge.postApprovalRequest` → native sheet → `__gatewayResolve` → `POST /send` | TCP first; `gateway.js` **does** serve `/health`, which is the HTTP fallback |

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
  `EngineWatchdog.start(port)` arms 5s liveness probes from here on.
- **DSH entry URL (why the bare origin is wrong)**: `dsh web` sits behind a
  browser-trust fence. A bare `http://127.0.0.1:<port>` returns **HTTP 401**
  with the body `dsh web authentication required; reopen the URL printed by
  dsh web.` — the WebView would sit on that error forever. The real entry
  point is printed on **stdout** at startup:

  ```
  dsh web: http://127.0.0.1:3979/?token=WGPhOq7G8rIFL4xUHZL4jBgxgiw5pCc-vHielseDNwI
  ```

  That tokenized URL answers **HTTP 303** (the auth handshake) and completes
  into the UI. Because the child's stdout used to be redirected straight into
  a log file, that URL was invisible to the app. So:

  ```
  spawn → AgentLaunch.pump(stdout) tees to dsh-logs/<agent>.log
        → AgentLaunch.extractLaunchUrl() picks the first loopback URL
        → service waits up to 2.5s for it → Running(url = tokenized URL)
        → WebView loads that URL
  ```

  Non-loopback URLs are rejected outright, so a chatty or hostile child cannot
  pull the WebView off-device. Backends that print no banner (`gateway.js`)
  fall back to the bare origin after the 2.5s timeout, which is correct for
  them. The token is the live credential for the whole local session, so
  `AgentLaunch.redactUrl` and `DiagnosticsStore` both mask `token=…` before it
  reaches Logcat or a pasted bug report.
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
  `bootstrap.log` (2s poll, secrets and `token=…` redacted) → one-tap
  clipboard export. Note that `harness.log` does not exist for any backend we
  have observed — see verified contracts below.

## Build

```bash
# Requires JDK 17 + Android SDK (compileSdk 34, build-tools 36.0.0)
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 95 JVM tests
```

Gradle is pinned to **8.7** via `gradle/wrapper/` — AGP 8.5.2 predates
Gradle 9 and is incompatible with it.

Sandboxed/CI note: if `~/.gradle` or `~/.android` is not writable, point both
at the workspace:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ANDROID_USER_HOME=$PWD/.android-home ./gradlew ...
```

## Runtime — PRoot + an Alpine rootfs

### Why a Linux userland at all

PRoot rewrites syscalls via `ptrace`. It does **not** emulate libc. The guest
loader it hands control to is the *guest's* loader, taken from the rootfs. So
an **Alpine aarch64 `node` runs natively inside an Alpine rootfs on Android**
— Android's bionic never enters the guest process.

That is why the runtime is "Linux Node under PRoot" rather than an
Android-native Node, and it was confirmed on a physical device
(Samsung SM-X115, Android 16, arm64-v8a, SELinux enforcing):

```
node -e 'console.log(process.version, process.arch)'  ->  v22.23.2 arm64
npm --version                                           ->  11.6.4
bash 5.2.37 | git 2.49.1 | rg 14.1.1 | curl 8.14.1 | python3 3.12.15
https.get('https://registry.npmjs.org/dsh')            ->  200
```

Node.js does not publish an *official* Android build — that is why we take the
Linux aarch64 binary from Alpine rather than from nodejs.org — but it was never
a blocker. See [PAYLOAD-RESEARCH.md](PAYLOAD-RESEARCH.md) for the options that
were weighed.

### musl, not glibc — the rule that bites later

The rootfs is Alpine/musl, so the `node` inside it must be musl-linked too.
Swapping in a glibc tarball (`node-vX-linux-arm64.tar.xz`) will simply not run:
it has `PT_INTERP=/lib/ld-linux-aarch64.so.1` and needs `libc.so.6`, and musl
provides none of those sonames (Alpine ships only
`/lib/ld-musl-*-aarch64.so.1`). The failure is a cryptic loader error, not a
clear "wrong libc". **Do not mix a glibc Node with this rootfs.**

For the same reason `nodejs-mobile` is not a candidate: its Android release zip
contains only `lib/arm64-v8a/libnode.so` plus headers and **no `node`
executable at all** — it is a JNI embedding kit, and its own BUILDING.md says
Android is not a supported platform.

### proot wrapper args

`AgentRuntime.prootWrapperArgs()` is pure so each rule can be pinned by
`ProotWrapContractTest`. Every bind below exists because its absence was an
*observed* on-device failure, not a precaution:

| Arg | Why it is there |
|---|---|
| `-r <runtimeRoot>` | the rootfs becomes the guest's `/` |
| `-b /proc` | `git` and many tools read `/proc/self`; PRoot's `-r` alone gives an empty tree |
| `-b /dev` | **without it there is no `/dev/null` and `git` dies immediately**: `could not open '/dev/null': No such file or directory` |
| `-b /sys` | same family of guest-side tooling that expects it |
| `-b <workspace>:/workspace` | the app-private/Saf-staged workspace, visible to the agent |
| `-w /workspace` | the child's CWD |
| `--link2symlink` | makes symlinks behave the way the rootfs expects |

### Guest environment

**PATH.** The host's Android `PATH` (`/product/bin:/apex/com.android.runtime/bin:…:/vendor/bin`)
otherwise leaks straight into the guest, where `cat`, `ls` and `uname` are all
"not found" — and any agent that spawns `git`/`rg`/`bash` **by name** fails.
`AgentRuntime.GUEST_PATH` puts the rootfs directories first and keeps the
Android tail last so a host tool stays reachable if genuinely needed:

```
/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin:/product/bin:/system/bin
```

`buildEnvironment(insideProot = true)` overrides `PATH`, `HOME` and `TMPDIR`
**only** when the child is actually inside PRoot; a native run keeps the host
values.

**DNS.** A stock Alpine minirootfs ships no `/etc/resolv.conf`, so guest DNS
fails and npm installs fail with a misleading network error.
`AgentRuntime.ensureGuestDns(context)` writes
`RESOLV_CONF_CONTENT` at boot whenever PRoot is active.

### Building the rootfs

`tools/build-alpine-rootfs.py` builds the payload **on macOS without Docker**.
`apk(8)` has no macOS build, but an `.apk` is just three concatenated gzip
streams (signature, control, data), so `zcat pkg.apk | tar -x` reproduces the
data segment directly. The script resolves the real dependency closure from
Alpine's signed index (`p:` providers, not guessed names), then unpacks it.

## Runtime payload — NOT bundled in the shipped APK

The APK builds and installs, but **the agent does not boot on a device yet**,
because the payload is not in the APK. There are only **two** artifacts now —
`node` is not one of them:

| Missing artifact | Expected location | Effect if absent |
|---|---|---|
| PRoot binary (aarch64 static) | `dsh-runtime/proot` | `buildCommand()` falls back to running the agent natively; rootfs paths won't resolve |
| Alpine 3.22 aarch64 rootfs, **with `nodejs`/npm/git/bash/ripgrep/curl/python3 preinstalled** | `dsh-runtime/rootfs.tar.gz` | `bootstrap.sh` fails with "Reinstall the app to restore bundled assets" |

Node lives inside `rootfs.tar.gz` as Alpine's `nodejs` apk, so
`app/src/main/assets/runtime-assets.json` no longer lists a separate
`node.tar.xz` asset.

This is deliberate for now: the first-boot UX degrades to a clear error card
rather than a crash. What remains is **packaging and hosting** — decide
between bundling (large APK) and downloading on first run, then host the files
and record their SHA-256. Feasibility is no longer the open question; the
runtime itself has been booted and Node verified on the device.

## Verified external contracts

These were confirmed by running the real tools, not inferred. Each entry lists
the evidence.

1. **`dsh web` is behind a browser-trust fence.** `GET /` on a running server
   returns **HTTP 401** with the body
   `dsh web authentication required; reopen the URL printed by dsh web.`
   The tokenized URL printed on stdout answers **HTTP 303** (auth handshake).
   Consequence: the app must load the *printed* URL, not the bare origin — see
   the DSH entry-URL flow above.
2. **`dsh web` takes `--profile web`, not a `web` subcommand.** Its options
   are exactly `--host`, `--no-open`, `--port`, `--trusted-host`, `-h/--help`.
   There is **no `--workspace`**; passing one prints
   `error: unknown option '--workspace'` and exits non-zero. The old argv
   (`web --host … --workspace …`) therefore failed three ways at once.
   `--no-open` is mandatory on a phone. `AgentCommandContractTest` pins this.
3. **DSH has no `/health` route.** `GET /health` returns **HTTP 404**. A
   watchdog that requires HTTP 2xx would declare a healthy agent dead and
   restart-loop it forever, so `EngineHealthProbe` treats a bare TCP connect
   as the primary liveness signal and treats *any* status (200, 401, 403, 404,
   5xx) as "alive and answering". `LivenessTest` and `EngineHealthProbeTest`
   pin this.
4. **`~/.dsh/logs/harness.log` does not exist.** No `.log` files of any kind
   are present under `~/.dsh/` after running the real CLI. `DiagnosticsStore`
   probes that path plus `<runtimeRoot>/.dsh/logs/harness.log` and renders
   "(not written by this backend)" when it is missing. This probe is currently
   dead weight — it needs a real source, or removal.
5. **Alpine Node runs inside PRoot on a real device.** `node v22.23.2 arm64`,
   `npm 11.6.4`, bash/git/ripgrep/curl/python3 and an HTTPS request to
   `registry.npmjs.org` returning 200 — all inside PRoot, as an unprivileged
   uid, with SELinux `Enforcing`. See the Runtime section above.

Still **not** verified, and worth checking before shipping:

- **`npm install -g @deepseek-ai/dsh` did not complete on-device.** The phone
  dropped off USB mid-install, so `dsh --version` was never observed running
  from a PRoot rootfs. npm itself works and the download was in flight, but
  treat this as unproven — and expect it to be slow under PRoot's `ptrace`
  overhead, which is an argument for pre-installing the CLIs into
  `rootfs.tar.gz` at build time.
- `--trusted-host <authority…>` ("extra authority the /api browser-trust fence
  accepts") is confirmed to exist, but we do not know whether the in-app
  WebView needs it to get past the fence. On-device verification required.
- `ActiveAgent.OPENCODE` still passes `--workspace /workspace`, and
  `opencode serve`'s flag set as a whole was carried over unverified. Unlike
  the DSH branch above, nobody has checked it against the real CLI.
