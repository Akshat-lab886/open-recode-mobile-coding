# OPEN RECODE (mobile coding)

A fully visual, touch-driven Android client for real AI coding agents — DeepSeek
Harness, Claude Code, Codex, and OpenCode. No terminal, no shell, no CLI.

Kotlin · Jetpack Compose · Material 3 · minSdk 29 · targetSdk 34

---

## ⚠️ Status: builds, installs, and launches — but the agent does not boot yet

This is an honest snapshot, not a finished product.

**Works today**
- Full Compose UI: control centre, workspace picker, agent/mode selection.
- AES-256-GCM Keystore-backed API key vault with silent env injection.
- Foreground-service orchestration with workspace mount, free-port allocation,
  loopback health checks, and crash recovery.
- First-boot bootstrap with a live progress bar + streaming log.
- 5-second watchdog with a friendly "Restart Engine" error card that preserves
  your workspace.
- Keyboard-docked input accessory bar (`/help`, `/stop-generation`, …).
- Hidden diagnostics sheet with secret-redacted log export.
- A standalone CLI→visual gateway (Node) with SSE streaming, approval
  interception, and a workspace file browser.
- 37 JVM tests, including a local-HTTP-server test proving the payload
  downloader rejects tampered bytes.

**Does not work yet**
- **No agent can actually start on a device.** See [Runtime payload](#runtime-payload).

---

## Runtime payload

The app needs three artifacts that are **not** in this repository:

| Artifact | What it is | Why it isn't committed |
|---|---|---|
| `proot` | ARM64 PRoot binary | Would need device verification |
| `rootfs.tar.gz` | Minimal Linux rootfs (e.g. Alpine aarch64) | ~4 MB, redistributable but unverified here |
| `node.tar.xz` | Node.js for Android/arm64 | **Node.js publishes no Android build at all** |

Because there is no official Node build for Android and no physical device is
available to validate a hand-built one, committing an unverified binary would
be worse than shipping none. So the payload is *described* in
[`app/src/main/assets/runtime-assets.json`](app/src/main/assets/runtime-assets.json)
and **downloaded on first run**, verified against SHA-256.

Until a source is configured the app says exactly what is missing instead of
crashing cryptically:

> Runtime payload not installed. Missing: node (Node.js runtime built for
> Android arm64) … Point the app at a payload source (see runtime-assets.json)
> and restart.

### Hosting a payload

1. Put the three files in a directory, one per `path` in the manifest.
2. Record each file's real SHA-256 in the manifest.
3. Set `baseUrl` to that directory (or override per-asset `url`).

Downloads land in a `.part` file and are only moved into place after the hash
matches, so an interrupted install can never be mistaken for a complete one.
Assets with a blank hash are refused outright rather than trusted.

---

## Build

Requires **JDK 17** and an Android SDK with platform 34 and build-tools 36.0.0.

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk  (~2.7 MB, R8)
./gradlew :app:testDebugUnitTest    # 37 JVM tests
```

Gradle is pinned to **8.7** — AGP 8.5.2 predates Gradle 9 and is incompatible
with it.

In a sandbox where `~/.gradle` or `~/.android` is not writable:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ANDROID_USER_HOME=$PWD/.android-home ./gradlew ...
```

### Signing

Signing material is **not** in this repository. To produce an installable
release build, create `keystore/signing.properties`:

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

…and place `keystore/release.jks` beside it. Without that file the release
build is simply unsigned rather than failing, so forks still get a working
debug APK. **Keep your key** — Android only accepts updates signed with the
same one.

---

## Architecture

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the module map, key flows, and
the **stdin ownership** table (the subtle part: each CLI's stdin has exactly
one owner, or approvals deadlock).

Quick orientation:

```
MainActivity ──▶ AgentRuntimeService (foreground service)
                    ├─ BootstrapManager   first-run setup, progress markers
                    ├─ AgentRuntime       PRoot/Node layout, argv, ports
                    ├─ ApiKeyVault        Keystore → env vars
                    └─ EngineWatchdog     5s /health polling, restart

UI: Compose shell → WebView (agent's own UI, or gateway chat)
                  → ModalBottomSheets (control centre / approvals / diagnostics)
                  → InputAccessoryBar docked above the keyboard
```

---

## Unverified external contracts

Assumed, and **not** confirmed against the real tools:

1. `dsh web` flags — `--host/--port/--workspace` are assumed.
2. Whether DSH web serves `/health` (the watchdog falls back to `/`).
3. The `harness.log` path (`~/.dsh/logs/`).

Each is a small fix once checked, but shipping unchecked means the watchdog may
flap or the agent may reject unknown flags.

## License

MIT — see [LICENSE](LICENSE).