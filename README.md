# OPEN RECODE (mobile coding)

A fully visual, touch-driven Android client for real AI coding agents — DeepSeek
Harness, Claude Code, Codex, and OpenCode. No terminal, no shell, no CLI.

Kotlin · Jetpack Compose · Material 3 · minSdk 29 · **targetSdk 28** (see
[Why targetSdk 28](#why-targetsdk-28))

---

## ⚠️ Status: builds, installs, and launches — but the agent does not boot yet

This is an honest snapshot, not a finished product.

**Works today**
- Full Compose UI: control centre, workspace picker, agent/mode selection.
- AES-256-GCM Keystore-backed API key vault with silent env injection.
- Foreground-service orchestration with workspace mount, free-port allocation,
  loopback liveness probes, and crash recovery.
- Startup capture of the agent's own entry URL (DSH serves its UI behind a
  tokenized, browser-trust-fenced URL printed on stdout — not at the bare
  origin), with non-loopback URLs refused and the token redacted.
- First-boot bootstrap with a live progress bar + streaming log.
- 5-second watchdog with a friendly "Restart Engine" error card that preserves
  your workspace.
- Keyboard-docked input accessory bar (`/help`, `/stop-generation`, …).
- Hidden diagnostics sheet with secret-redacted log export.
- A standalone CLI→visual gateway (Node) with SSE streaming, approval
  interception, and a workspace file browser.
- A working Linux runtime, verified on a physical device: Node v22.23.2 arm64
  (Alpine aarch64 under PRoot, unprivileged uid, SELinux enforcing), npm 11.6.4,
  bash, git, ripgrep, curl and python3 — all booting inside the guest.
- 95 JVM tests, including a local-HTTP-server test proving the payload
  downloader rejects tampered bytes, contract tests pinning the DSH argv
  against the CLI's real `--help`, contract tests pinning the PRoot binds, and
  liveness tests proving that 404/401 responses read as alive.

**Does not work yet — the real blocker**
- **No agent can start on a device from the shipped APK.** Every component has
  been proven individually on a physical Samsung SM-X115 (Android 16), and the
  guest toolchain runs perfectly — but **PRoot cannot run inside a normal app
  process**, and that is a platform restriction, not a bug we can fix here:

  ```
  # same binary, same arguments, two launch contexts
  run-as (adb shell):    Seccomp: 0  Filters: 0  ->  proot works
  app process (zygote):  Seccomp: 2  Filters: 1  ->  proot exits 255, no output
  ```

  Zygote-spawned apps inherit an Android seccomp filter that `run-as` does not.
  That asymmetry is why every component appeared to work under `adb shell` and
  then failed inside the app. PRoot 5.3.0's `process_vm_readv`/`writev`
  accelerator has no opt-out (`PROOT_NO_SECCOMP` disables only its own filter),
  and `v5.5.0` — the current release — ships **no aarch64 prebuilt binary** and
  no Android fix.

  The guest runtime itself is real and verified on-device: Node v22.23.2 arm64,
  npm 11.6.4, git 2.49.1, ripgrep 14.1.1, python3 3.12.15, curl 8.14.1, child
  processes, and TLS to the npm registry. See [Runtime](#runtime).

### What would unblock it

1. **A PRoot build that avoids `process_vm_*`**, or one compiled without that
   accelerator. Needs a cross-toolchain or an aarch64 Linux host — not
   available in this environment.
2. **Dropping PRoot** for a different virtualisation strategy that does not need
   `ptrace`/`process_vm` (e.g. a pure-Java ELF loader with an interpreter, at a
   large performance cost).
3. **Dropping self-containment** and driving a companion app (Termux) that
   already holds `targetSdk 28` and can exec binaries. This works today on the
   test device but is not a self-contained product.

Nothing here is a packaging problem, and no amount of download-hosting work
will change it. We would rather say so plainly than ship an app that cannot
start.

### Why targetSdk 28

Android removed the implicit `execute_no_trans` permission for apps targeting
API 29+. At `targetSdk 34`, `execve()` was denied in **every** app-writable
location — `filesDir`, `getExternalFilesDir()` and `cacheDir` alike. Termux
uses `targetSdk 28` for exactly this reason. This APK is published on GitHub
Releases, not the Play Store (which mandates 34+), and the corresponding
`ExpiredTargetSdkVersion` lint check is disabled in `app/build.gradle.kts`.

---

## Runtime

The app runs **Linux aarch64 Node inside PRoot**, not an Android-native Node.

PRoot rewrites syscalls via `ptrace` but does **not** emulate libc, so the guest
loader comes from the rootfs. An Alpine aarch64 `node` therefore runs natively
inside an Alpine rootfs on Android — bionic never enters the guest process.
This was verified on a Samsung SM-X115 (Android 16, arm64-v8a, SELinux
enforcing):

```
node -e 'console.log(process.version, process.arch)'  ->  v22.23.2 arm64
npm --version                                           ->  11.6.4
bash 5.2.37 | git 2.49.1 | rg 14.1.1 | curl 8.14.1 | python3 3.12.15
https.get('https://registry.npmjs.org/dsh')            ->  200
```

Node.js publishes no *official* Android build, which is why we take the Linux
aarch64 binary from Alpine rather than from nodejs.org. That was never the
blocker.

**The rootfs is musl, so the Node inside it must be musl-linked too.** Dropping
in a glibc tarball (`node-vX-linux-arm64.tar.xz`) will simply not run: it needs
`libc.so.6` and `/lib/ld-linux-aarch64.so.1`, and musl provides neither. The
failure is a cryptic loader error, not a clear "wrong libc" — so don't mix them.

`nodejs-mobile` is not a substitute either: its Android zip contains only
`lib/arm64-v8a/libnode.so` plus headers and **no `node` executable**. It is a
JNI embedding kit, and its own BUILDING.md says Android is unsupported.

Each PRoot bind and the guest `PATH`/`DNS` handling exist because their absence
was an observed device failure — `/dev` alone breaks `git` outright, and the
host's Android `PATH` leaking into the guest makes `cat`/`ls`/`uname`
"not found". [`ARCHITECTURE.md`](ARCHITECTURE.md) has the per-argument table
and the reasoning.

---

## Runtime payload

Two artifacts, neither committed here:

| Artifact | What it is |
|---|---|
| `proot` | PRoot v5.3.0 aarch64 **static** binary (rootless syscall translator) |
| `rootfs.tar.gz` | Alpine 3.22 aarch64 rootfs with `nodejs`, npm, git, bash, ripgrep, curl, python3 and CA certs preinstalled |

**Node is not a separate download.** It ships *inside* `rootfs.tar.gz` as
Alpine's own `nodejs` apk, so `runtime-assets.json` no longer lists a
`node.tar.xz` asset.

Build the rootfs with [`tools/build-alpine-rootfs.py`](tools/build-alpine-rootfs.py),
which works **on macOS without Docker**: `apk(8)` has no macOS build, but an
`.apk` is three concatenated gzip streams (signature, control, data), so
`zcat pkg.apk | tar -x` extracts the data segment directly. The full
investigation — including the options that were rejected and why — is in
[`PAYLOAD-RESEARCH.md`](PAYLOAD-RESEARCH.md).

So the payload is *described* in
[`app/src/main/assets/runtime-assets.json`](app/src/main/assets/runtime-assets.json)
and **downloaded on first run**, verified against SHA-256.

Until a source is configured the app says exactly what is missing instead of
crashing cryptically:

> Runtime payload not installed. Missing: rootfs (Alpine aarch64 rootfs) …
> Point the app at a payload source (see runtime-assets.json) and restart.

### Hosting a payload

1. Put the two files in a directory, one per `path` in the manifest.
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
./gradlew :app:testDebugUnitTest    # 95 JVM tests
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
                    ├─ AgentLaunch        tees stdout, scrapes the entry URL
                    ├─ ApiKeyVault        Keystore → env vars
                    └─ EngineWatchdog     5s TCP-first liveness, restart

UI: Compose shell → WebView (agent's own UI, or gateway chat)
                  → ModalBottomSheets (control centre / approvals / diagnostics)
                  → InputAccessoryBar docked above the keyboard
```

---

## Verified external contracts

These were confirmed by running the real tools. What was assumed turned out to
be wrong in three places, and the fixes are in:

1. **`dsh web` flags.** The correct invocation is `--profile web`, not a `web`
   subcommand, and there is no `--workspace` option at all. The old argv
   (`web --host … --port … --workspace …`) was rejected three ways over: unknown
   command, unknown option, and no `--no-open` (so the server would have tried
   to launch a desktop browser on the device). Now:
   `node dsh.js --profile web --host 127.0.0.1 --port <port> --no-open`.
   Pinned by `AgentCommandContractTest`.
2. **`/health` on DSH.** There is no such route — `GET /health` returns
   **404**, and `GET /` returns **401** because `dsh web` sits behind a
   browser-trust fence. A watchdog that treated non-2xx as "dead" would have
   killed a perfectly healthy agent and restart-looped it forever. The watchdog
   now treats a bare TCP connect as the primary liveness signal, and any HTTP
   status (200/401/403/404/5xx) as alive. The visual gateway does serve
   `/health`; that route is now the HTTP fallback rather than the primary probe.
3. **`harness.log` — confirmed absent.** No `.log` files exist anywhere under
   `~/.dsh/` after running the real CLI, so `DiagnosticsStore`'s probe of
   `~/.dsh/logs/harness.log` (and its in-runtime equivalent) currently finds
   nothing and renders "(not written by this backend)". That tab needs a real
   source, or should be removed. Until then the agent log
   (`dsh-logs/<agent>.log`) is the only useful log.

Two more findings worth knowing:

- **`dsh web` does not serve its UI at the bare origin.** `GET /` returns 401
  with the body `dsh web authentication required; reopen the URL printed by
  dsh web.` The real entry point is printed on **stdout** at startup and carries
  a session token. The app now tees the child's stdout, scrapes that banner
  (rejecting non-loopback URLs), and loads the tokenized URL. The token is
  redacted from logs and diagnostic exports.
- **`--trusted-host <authority…>`** exists ("extra authority the /api
  browser-trust fence accepts") but we have not verified whether the in-app
  WebView needs it to get past that fence. It still needs an on-device check.

Also still unverified: `opencode serve`'s flag set, including whether it really
accepts `--workspace`.

And one that matters more than it sounds: **`npm install -g @deepseek-ai/dsh`
never completed on-device.** The phone dropped off USB mid-install, so
`dsh --version` running out of a PRoot rootfs has not actually been observed —
npm itself works and the download was in flight, but do not read that as proof.
It is also expected to be slow under PRoot's `ptrace` overhead, which is an
argument for pre-installing the agent CLIs into `rootfs.tar.gz` at build time
rather than installing on the phone.

## License

MIT — see [LICENSE](LICENSE).