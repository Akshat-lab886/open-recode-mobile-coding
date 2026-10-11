# v0.2.0 — Contracts verified on a real device

Everything in this release was validated on a physical Samsung SM-X115
(Android 16, API 36, arm64-v8a, SELinux enforcing), not by reading code. The
device found nine real bugs that no amount of code review had surfaced.

## ⚠️ Read this first: the agent still cannot start on a device

The blocker is a platform restriction, not a missing feature:

PRoot's own high-verbosity trace, captured from inside the app, names the cause:

```
proot info: vpid 1: translate("/" + "/bin/busybox")
proot info: vpid 1: terminated with signal 31     <-- SIGSYS, "bad system call"

app process (zygote-spawned):  Seccomp: 2  Filters: 1   ->  SIGSYS
run-as child (identical argv): Seccomp: 0  Filters: 0   ->  works
```

Zygote-spawned apps inherit an Android seccomp filter that `run-as` does not,
and that filter rejects a syscall PRoot requires. This asymmetry is why every
component appeared to work under `adb shell` and then failed inside the app —
"it works when I test it manually" was true and useless.

PRoot 5.3.0's `process_vm_readv`/`process_vm_writev` accelerator has no opt-out
(`PROOT_NO_SECCOMP` disables only *its own* seccomp filter and does not help —
verified), and v5.5.0, the current release, ships **no aarch64 prebuilt binary**
and no Android fix.

**Everything except this one join is proven working on the device:** Node
v22.23.2 arm64, npm 11.6.4, git 2.49.1, ripgrep 14.1.1, bash 5.2.37,
python3 3.12.15, curl 8.14.1, child processes, and TLS to the npm registry —
all running inside the guest from the shipped tarball.

Ways forward are listed in the README. We would rather ship this plainly than
publish an APK that cannot start.

## Fixed

**DSH CLI contracts** — all previously assumed, now verified against the CLI:
- The web server is a **profile**, not a subcommand: `dsh --profile web`.
  `dsh web` is an unknown command.
- `--workspace` is rejected outright (`error: unknown option`), exiting non-zero.
- `GET /health` returns **404** and `/` returns **401**, so any HTTP-status
  health check would restart-loop a healthy agent. Liveness now treats any
  HTTP response as alive; only transport failures are unhealthy.
- The entry URL is tokenized (`?token=…`, answers 303); the bare origin 401s
  forever. `AgentLaunch` captures it while teeing stdout to the log, refuses
  non-loopback URLs so a hostile child can't redirect the WebView off-device,
  and redacts the token.

**Android platform realities**
- Apps targeting API 29+ cannot `execve()` anything in their own writable
  storage — verified denied in `filesDir`, `getExternalFilesDir()` and
  `cacheDir`. `targetSdk` is now **28**, as Termux does.
- PRoot needs `PROOT_TMP_DIR`; without it every spawn dies with
  `can't create temporary file`, because an app cannot write `/tmp`.
- `ProcessBuilder` gives no login shell, so an unset `HOME` aborted the entire
  bootstrap under `set -u`.
- The bootstrap CWD must exist *inside* the guest rootfs, or PRoot hard-exits
  255 with `can't chdir(...)`.
- A `2>/tmp/...` redirect failure was being reported to the user as a broken
  rootfs download.
- `tar` warns on guest symlinks because Android's `/bin` is a symlink to
  `/system/bin`; the runtime is now validated by a **runnable node binary**
  rather than by `tar`'s exit code.
- First boot no longer re-downloads 283 MiB via `apk` — the shipped rootfs is
  already complete.

**Correctness**
- DSH's real entry point is `lib/node_modules/@deepseek-ai/dsh/lib/bin.js`.
  The previously assumed `<agents>/dsh/bin/dsh.js` never existed.
- Under PRoot, argv must use **guest** paths; host paths do not exist in the
  guest's view.
- `tools/build-alpine-rootfs.py` now indexes `main` **and** `community` (npm
  and ripgrep live in community) and parses space-separated `p:` provides,
  which had silently dropped every provider but the first. Unresolved providers
  are now a hard failure rather than a warning.

## Assets

| File | Size |
|---|---|
| `app-release.apk` | 2.7 MB — signed release build |
| `app-debug.apk` | 56 MB — debuggable, for `run-as` diagnostics |

The runtime payload (`proot` + `rootfs.tar.gz`) is **not** bundled; it is
fetched per the manifest in `app/src/main/assets/runtime-assets.json`.

**Upgrading note:** install over v0.1.0 only if you have `keystore/release.jks`
from that build. Android refuses updates signed with a different key. Back that
file up — it is gitignored and cannot be recovered.

## Tests

95 JVM tests, 0 failures.