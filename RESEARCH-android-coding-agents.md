# Running `@openai/codex` / `@anthropic-ai/claude-code` on Android (aarch64)

**Date of research:** Oct 2026. Registry state verified directly against registry.npmjs.org.

## 0. Ground truth: no Android artifact is published (HIGH confidence)

| Check | Result |
|---|---|
| `@openai/codex` (0.162.1) optionalDeps | linux-x64, linux-arm64, darwin-x64/arm64, win32-x64/arm64 — **6 only** |
| `@openai/codex` **all** dist-tags (`latest`,`alpha`,`native`,`beta`, per-platform) | linux-arm64, linux-x64, darwin-*, win32-* — **no android/aarch64 tag anywhere** |
| `@anthropic-ai/claude-code` (2.1.296) optionalDeps | + `linux-arm64-musl`, `linux-x64-musl`. **No android variant.** `stable`(2.1.287) also lacks it |
| `registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-android` | **404** |
| `registry.npmjs.org/@openai/codex-android-arm64` | **404** |
| codex `README.md` + `docs/install.md` for `android\|termux\|wasm\|bionic` | **zero matches — no documented Android/Termux install path** |

**The root blocker** (device-verified, [herdr#3571](https://github.com/herdrdev/herdr/issues/3571)): Android's dynamic linker only executes **PIE (`ET_DYN`)**. Both agents ship **`ET_EXEC`** (`e_type: 2`, base `0x400000`, no `PT_INTERP`). Termux preloads `libtermux-exec.so`, which intercepts `execve` and rejects them: `"unexpected e_type: 2"`. There is no client-side workaround; `TERMUX_EXEC_OPTOUT=1` breaks all execs on affected builds. This is also why the npm install itself fails: `process.platform === "android"` but the package declares `os:["linux"]` → `EBADPLATFORM`.

## 1. PRoot (`proot-distro install ubuntu`) — **CONDITIONAL (works, degraded)** — confidence HIGH

- [codex#6757](https://github.com/openai/codex/issues/6757) — "Codex CLI fails under Termux + proot Ubuntu on Android: `prctl(PR_SET_DUMPABLE, 0) failed` (0.42 works, 0.43+ broken)". The binary **does** launch in proot; OpenAI's `etraut-openai` **closed it as completed with no fix** (Mar 2026).
- [codex#18120](https://github.com/openai/codex/issues/18120) — OpenAI maintainer, verbatim: *"There appear to be many bugs or limitations in termux that prevent the Codex TUI from working well in this environment. You may have better luck with a different terminal emulator."*
- **Sandbox (bubblewrap) breaks in proot**: [codex#30153](https://github.com/openai/codex/issues/30153) (`bwrap: fchdir to oldroot`), [codex#47402](https://github.com/openai/codex/issues/47402) (`cannot establish app-server socket mount isolation`). Workaround: approve commands to run unsandboxed.
- **Perf penalty is real**: ptrace tax. In [#50270](https://github.com/anthropics/claude-code/issues/50270), @escape0707 reports proot causes *"every tool call… makes Codex wait for around 40 seconds"*. PRoot's own README concedes ptrace makes filesystem-heavy work "noticeably slower".
- Claude Code **does** work in proot: @ferrumclaudepilgrim — *"In proot-distro inside of Termux I can successfully run 2.1.185 with no problems"* (until a Bun SVE crash, bun#32489).

## 2. Termux native (bare, no proot) — **DOES NOT WORK out of the box** — confidence HIGH

`npm i -g @openai/codex` / `@anthropic-ai/claude-code` both fail:
- [codex#37262](https://github.com/openai/codex/issues/37262) — `EBADPLATFORM` then `Missing optional dependency @openai/codex-linux-arm64`. The JS launcher **has** `case "android":` mapping to `aarch64-unknown-linux-musl`, but npm refuses the install. Open.
- [claude-code#50270](https://github.com/anthropics/claude-code/issues/50270) — 72 comments, still open. `install.cjs` prints `Unsupported platform: android arm64`; `bin/claude.exe` is a ~155-byte stub → `Error: claude native binary not installed`.

**THE transition discussion you asked for** — Anthropic's **`ant-kurt` (author_association: COLLABORATOR), 2026-04-23**, verbatim in #50270:

> "Claude Code 2.1.113 has switched to a native executable (after npm deprecation was announced in January), which drops support for some platforms. **We may add support for Android in the future — this requires either a bun target for `android-arm64` or static musl.** You can pin to the last JS bundle release …@2.1.112 to continue using Claude Code for now."

Timeline: v2.1.113 (2026-04-18) = last JS bundle = v2.1.112. Fix request [claude-code#72620](https://github.com/anthropics/claude-code/issues/72620) was closed as duplicate by `bcherny` (Anthropic) — **no android binary has shipped since**. Six months later the `stable` tag moved to 2.1.287, but **2.1.287 still has no android optionalDep**; I could NOT verify whether `stable` now installs on Termux.

Additional native-Termux blockers even after patching (all open issues):
- [codex#11809](https://github.com/openai/codex/issues/11809) — DNS: no `/etc/resolv.conf` on Android → musl resolver falls back to `127.0.0.1:53`; both auth flows fail. Reopened by `etraut-openai` after the reporter showed it is NOT the proot bug. The reporter states the fix is **building for `aarch64-linux-android`**.
- [codex#26277](https://github.com/openai/codex/issues/26277) — `flock(2)` returns `ENOTSUP` on `/data`; `lock() not supported`. Hard crash on every `codex exec`. Needs `#[cfg(not(target_os="android"))]`.
- [claude-code#15637](https://github.com/anthropics/claude-code/issues/15637) — hardcoded `/tmp/claude` paths.

## 3. ELF patching — **WORKS, device-validated, with a ROM caveat** — confidence HIGH

`patchelf` + Termux's **glibc-runner** (from [termux-pacman](https://termux-pacman.dev/) / [glibc-packages](https://github.com/termux/glibc-packages)) is the one approach with reproducible multi-device success.

- **[gtbuchanan/claude-code-termux](https://github.com/gtbuchanan/claude-code-termux)** (24★, active Oct 2026) — "downloads + ELF-patches Anthropic's linux-arm64 build", ships a `.deb` + `install.sh` patching `PT_INTERP` to glibc-runner's `ld.so`, plus a **uname shim** (Bun crashes on kernel <5.10).
- **Confirmed working** in #50270 on: Pixel 2/Android 11/4.4 (Claude 2.1.280), Pixel 7 (`panther`), Pixel 10 Pro XL/Android 17 QPR, SM-F946U Fold5/Android 16 (2.1.185), Termux `termux-exec` 2.4.0. Requires termux-exec **2.x** from `packages-cf.termux.dev`, not the frozen 1.9 on `termux.net`.
- ⚠️ **ROM-dependent**: @salviz's Galaxy S23 (`dm3q`) — *"the Android kernel substitutes `/system/bin/linker64` regardless of the patched `PT_INTERP`"* — patchelf **fails there** while the identical kernel line works on the Fold5. Same GKI, different product tree.
- Known regressions from this route: clearing `LD_PRELOAD` breaks shebang scripts (`pnpm`) but fixes Claude's bundled grep/rg; `#101210` shows a separate SVE/`cntb` SIGILL even inside Android's AVF Linux Terminal.
- **Patching is a multi-stage whack-a-mole, not one patchelf call** (reported in #50270): (1) Bun rejects `ET_EXEC` → `e_type: 2`; (2) after flipping the header to `ET_DYN`, bionic rejects it with *"TLS segment is underaligned: alignment is 8, needs to be at least 64 for ARM64 Bionic"*; (3) then `PT_INTERP` may still be hijacked to `/system/bin/linker64`. One commenter got 2.1.207 native **only on a Pixel 7 with a custom kernel**. Safe fallback = pin **2.1.112**.
- **Codex equivalent**: **[xkxxs/codex-termux](https://github.com/xkxxs/codex-termux)** (6★, Shell) claims it runs the *official, byte-for-byte unmodified* musl binary natively — but only 6 stars, low corroboration, and it is contradicted by #37262/#11809. **Treat as low confidence.**

## 4. WASM — **DOES NOT EXIST** — confidence HIGH

No `@openai/codex-wasm` / `claude-code-wasm` on npm (searched; only unrelated `workerd`, `zxing-wasm`, …). No wasm mention in the codex repo README, install docs, or dist-tags. Second-hand (unverified by me): a delegated search of all 5436 published `@openai/codex` versions found zero containing "wasm", and no wasm crate among the ~110 `codex-rs` workspace members; codex#2100 "Request: Run inside WASM" is closed with 0 comments.

⚠️ **Could NOT verify** that anyone has ever *attempted* a `wasm32-wasip1` port, nor whether it is feasible. Absence of a shipped artifact is not proof of non-portability — `codex-rs` uses tokio/reqwest/Unix sockets and its own process sandboxing, all of which are hostile to WASI preview 1. Do not plan around WASM, but do not claim it was tried and failed.

## 5. qemu-user — **PATH CLOSED (Termux does not package it)** — confidence MEDIUM-HIGH

No Android/Termux project found. Second-hand (unverified by me): direct probes of `termux/termux-packages` show all four qemu package names (`qemu`, `qemu-user`, `qemu-user-x86-64`, `qemu-user-x86`) return 404, while `proot-distro` and `termux-exec` exist. Termux ships a **glibc compat layer** instead ([termux/glibc-packages](https://github.com/termux/glibc-packages)). Note: the widely-cited `packages.termux.dev/api/search?q=…` endpoint does not exist (404) — do not cite it.

Caveat: I could not verify exact package names inside `termux/glibc-packages` (nonstandard layout, API rate-limited).

## 6. Building from source for Android/bionic — **WORKS (for codex only)** — confidence MEDIUM-HIGH

**[DioNanos/codex-termux](https://github.com/DioNanos/codex-termux) (487★)** is the strongest artifact found. README: *"builds native Android ARM64 binaries for Termux"*, published as `@mmmbuto/codex-cli-termux`, *"built with the API 29 NDK target"*. Patches: `termux-open-url` browser login, self-update redirect, ripgrep via PATH. Also [bash0816/Codex-Termux](https://github.com/bash0816/Codex-Termux), [wallentx/codex-termux](https://github.com/wallentx/codex-termux) (auto-mirrors releases with Termux fixes).

**Claude Code is NOT open source** — no source build possible; only binary patching.

**Upstream never took Android support (UNVERIFIED — high-impact, flagged).** A delegated search reports PR #6819 *"Add Android/Termux support to Codex CLI"* (NDK integration + vendored OpenSSL + `termux-build.yml`) was **closed and never merged**, and that codex#24507 documents a working native `aarch64-linux-android` build whose only failure was `ld.lld: unable to find -lc++_static` (Termux ships `libc++_shared.so`, no static runtime; workaround is an `INPUT(-lc++_shared)` shim). **I was unable to verify either** — GitHub API rate-limited and the search backend then failed with HTTP 402. Treat the PR #6819 claim as second-hand until re-checked; it is consistent with everything I did verify (no Android dist-tag, no docs, no merged support), but it is not something I confirmed.

## 7. Purpose-built Android apps — **MIXED, and ⚠️ SUPPLY-CHAIN RISK** — confidence HIGH

Real: [ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android) (267★), [gtbuchanan/claude-code-termux](https://github.com/gtbuchanan/claude-code-termux), [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux). The only projects that actually **embed a local arm64 agent** are the Termux forks.

The high-profile "app" projects do **not** run the agent on-device — they are remote bridges over the CLI's app-server. Second-hand: `friuns2/codexui` now redirects to `friuns2/codex-mobile` (958★, TypeScript), exposing the Codex app-server over HTTP via a `codexapp` Node bridge + cloudflared tunnel, and **requires the codex CLI preinstalled**. `claude-yolo/claude-yolo` is an 8★ tmux auto-approve wrapper that bundles nothing.

**Best end-to-end evidence** remains gtbuchanan/claude-code-termux, confirmed by miadisabelle (2026-09-23, #50270): v2026.8.60 running Claude Code **2.1.280** on a Pixel 2 / Android 11 / kernel 4.4 — TUI opened, Claude.ai login succeeded, and a real `--model` request completed.

🚨 **Do not recommend:** [`codexui-android` supply-chain compromise](https://corgea.com/research/codexui-android-openai-token-stealer) ([Aikido report](https://www.aikido.dev/blog/codex-remote-ui-steals-ai-tokens)). Published npm builds ≥0.1.82 contain code **absent from the GitHub source** that reads `~/.codex/auth.json` and XOR-exfiltrates the refresh token to `sentry.anyclaw[.]store/startlog`. Android apps ("OpenClaw Codex Claude AI Agent", `codex.app`) `pnpm add codexui-android@latest` **unpinned at runtime**. Unpinning means a clean APK pulls a malicious release. Any recommendation here must not point at that package.

## Bottom line

| Approach | Verdict |
|---|---|
| PRoot Ubuntu/Debian | **Conditional** — launches; sandbox + TUI fragile; big ptrace penalty |
| Bare Termux, stock npm | **Does not work** — `EBADPLATFORM` + `ET_EXEC` rejection |
| PRoot + official JS pin (CC ≤2.1.112) | Works but frozen, 85+ versions behind |
| **patchelf + glibc-runner (Termux)** | **Best verified path** — ROM-dependent |
| **Fork/NDK build for bionic (codex)** | **Works** — DioNanos/codex-termux, 487★ |
| WASM | Does not exist |
| qemu-user | Unknown — no evidence |
| Bundled Android app | Works, but see supply-chain warning |