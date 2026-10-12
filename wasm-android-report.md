# Running `codex` / `claude-code` on Android (aarch64, bionic) — evidence review

## 1. WASM builds — **VERDICT: does not exist (no evidence it works). Confidence: HIGH**

- `@openai/codex`: **0 of 5436 published versions** contain "wasm". All dist-tags are platform triples (`darwin|linux|win32` × `x64|arm64`); latest `0.162.1`. No `@openai/codex-wasm` on npm.
- No wasm crate in `codex-rs`: I listed the full workspace via `api.github.com/repos/openai/codex/contents/codex-rs` — ~110 members, **no wasm target crate** (`v8-poc` is a V8 engine probe, not a wasm build).
- [codex#2100 "Request: Run inside WASM"](https://github.com/openai/codex/issues/2100) — **closed, 0 comments** (no maintainer engagement).
- claude-code published matrix, checked 2026-08-22 in [#50270](https://github.com/anthropics/claude-code/issues/50270): `linux-arm64-musl`, `linux-arm64`, `linux-x64-musl` all ship; **`linux-arm64-android` not published**. No wasm package.

**COULD NOT VERIFY:** whether anyone has *attempted* a `wasm32-wasip1` port, and whether it is even feasible. No evidence either way — the crates use tokio/reqwest/Unix sockets/process sandboxing. Don't assume portability.

## 2. qemu-user on Android — **VERDICT: not available; Termux does not package qemu. Confidence: MEDIUM-HIGH**

- The endpoint you gave, `https://packages.termux.dev/api/search?q=qemu`, returns **404 — that API does not exist**. Don't cite it.
- Probed `raw.githubusercontent.com/termux/termux-packages/master/packages/<p>/build.sh`: `qemu`, `qemu-user`, `qemu-user-x86-64`, `qemu-user-x86` → **all 404**. `proot-distro` and `termux-exec` → 200 (they exist).
- **What Termux actually ships instead** is a glibc compatibility layer: [termux/glibc-packages](https://github.com/termux/glibc-packages) (official Termux org, mirror of `termux-pacman/glibc-packages`), installed via a `glibc-repo`. Runtime stack reported firsthand by salviz on a Pixel 10 Pro XL in [#50270](https://github.com/anthropics/claude-code/issues/50270): `termux-exec 1.9`, `glibc-runner 2.0-3`, `patchelf-glibc 0.18.0`, "installed via `apt` from the `termux-glibc` repo".
- **COULD NOT VERIFY:** exact package names/layout inside `termux/glibc-packages` (GitHub API rate-limited; raw paths 404 — it uses a Debian-style layout, not `packages/<name>/build.sh`).

## 3. Building `codex-rs` for bionic — **VERDICT: works, and is shipped by a third party. Confidence: HIGH**

**Yes — someone did the `aarch64-linux-android` build, and it runs.** [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux) publishes "native Android ARM64 binaries for Termux", release binary "built with the API 29 NDK target", on npm as `@mmmbuto/codex-cli-termux` (upstream base Codex `rust-v0.162.1` → `0.162.1-termux.1`).

Corroborating primary sources in `openai/codex`:

| Issue/PR | State | Evidence |
|---|---|---|
| [#24507](https://github.com/openai/codex/issues/24507) | **closed** | Rust target `aarch64-linux-android`, *native on-device build*, codex-rs 0.133.0. Only failure was `ld.lld: unable to find -lc++_static` (Termux ships `libc++_shared.so`, no NDK static runtime). Fix/workaround: `INPUT(-lc++_shared)` shim + `-C link-arg`. |
| [#11809](https://github.com/openai/codex/issues/11809) (wallentx) | **open** | DNS/flock failures; states an `aarch64-linux-android` build resolves them. `etraut-openai` (**CONTRIBUTOR**) replied twice → maintainer reply confirmed. |
| [PR #6819](https://github.com/openai/codex/pull/6819) | **closed, NOT merged** | "Add Android/Termux support": NDK integration, vendored OpenSSL, `.github/workflows/termux-build.yml`. **Never landed upstream.** |
| [#37262](https://github.com/openai/codex/issues/37262), [#36318](https://github.com/openai/codex/issues/36318), [#23679](https://github.com/openai/codex/issues/23679) | open | npm `EBADPLATFORM`, auth failures, TUI hangs on Termux |

**Net:** upstream ships no Android target; a community fork proves the Rust tree cross-compiles to bionic and runs.

## 4. Purpose-built Android apps — **VERDICT: real projects exist, but almost none bundle an agent. Confidence: HIGH**

- **`friuns2/codexui` → redirects to [friuns2/codex-mobile](https://github.com/friuns2/codex-mobile), 958 stars**, TypeScript. **Approach: NOT on-device.** It is `codexapp`, a Node bridge that starts Codex `app-server` and exposes it over HTTP + a `cloudflared` tunnel. It requires the codex CLI already installed in the environment — in Termux that means the fork above.
- **`claude-yolo/claude-yolo`, 8 stars**, Shell — a tmux wrapper auto-approving prompts. Not an Android app, bundles nothing; works on Termux only because Claude Code runs there at all.
- **Actually bundling a local arm64 agent:** [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux) (NDK ELF + npm), [ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android), [gtbuchanan/claude-code-termux](https://github.com/gtbuchanan/claude-code-termux). Confirmed end-to-end by miadisabelle (2026-09-23, [#50270](https://github.com/anthropics/claude-code/issues/50270)): `gtbuchanan/claude-code-termux 2026.8.60` running **Claude Code 2.1.280** on Pixel 2 / Android 11, login + a real model request succeeded.
- **Claude Code has no bionic build.** Community ELF patching is required and is device-dependent. Documented failures (notf0und): `e_type: 2` (Bun rejects ET_EXEC); after patching to ET_DYN, bionic: *"TLS segment is underaligned: alignment is 8, needs to be at least 64 for ARM64 Bionic."* shaman4ik reports 2.1.207 native working on a Pixel 7 **with a custom kernel**; salviz reports bionic hijacking `PT_INTERP` on stock Pixel 10 Pro XL (Android 17) and Samsung S23 Ultra. Fallback remains pinning `2.1.112` (last JS entry point).
- [Cosyra](https://cosyra.com/guides/codex-cli-on-android.html) (Google Play) — **cloud Ubuntu 24.04 container, not on-device**; source itself confirms OpenAI ships macOS/Linux(musl)/Windows only.

## Not verified / caveats
- Termux's package-search API is 404; my qemu absence finding is from direct `raw.githubusercontent` probes of `termux/termux-packages` (404 on all four names), not from a search API.
- `glibc-runner` package name is from a user report in-issue, not a direct package-index read.
- No evidence exists in either direction on `wasm32-wasip1` feasibility for `codex-rs`.