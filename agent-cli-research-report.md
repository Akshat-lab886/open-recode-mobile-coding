# Research Report: Antigravity CLI & Hermes Agent

Research date: 2026-10-12. Sizes/versions pulled live from the npm registry and GitHub API.

---

## 1. Antigravity CLI

### Identity

- **Official CLI entrypoint name: `agy`** (binary `agy`, invoked as `agy`). Version observed in the wild: `1.1.10`; latest GitHub release at time of query: **`1.3.3`** (published 2026-10-10).
- It is **the terminal half of Google's Antigravity agentic IDE**, sharing one core agent engine with the Antigravity 2.0 GUI app. There *is* a genuine terminal/CLI-first agent mode: an interactive TUI plus a headless/print mode.
- **Yes, Antigravity CLI is the Gemini CLI lineage — Gemini CLI is being replaced by it.** On 2026-05-19 a Gemini CLI maintainer announced Google is "transitioning our terminal experience from Gemini CLI to the brand-new Antigravity CLI." Gemini CLI keeps serving paid Gemini/Gemini Enterprise Agent Platform API keys but stopped serving Google AI Pro/Ultra/free tier on **2026-06-18**. A migration guide exists at `antigravity.google/docs/gcli-migration`, and the CLI ships `agy plugin import gemini`.
- **Antigravity CLI is closed source.** The GitHub repo has **no license and no detected language** — it is a distribution repo only (README, CHANGELOG.md, `examples/`, a demo GIF). Contrast: Gemini CLI remains Apache-2.0 open source.

### Install

```bash
# macOS / Linux (incl. ARM Linux)
curl -fsSL https://antigravity.google/cli/install.sh | bash

# Windows PowerShell
irm https://antigravity.google/cli/install.ps1 | iex

# Windows CMD
curl -fsSL https://antigravity.google/cli/install.cmd -o install.cmd && install.cmd && del install.cmd
```

- Installs to **`~/.local/bin/agy`** (add to `PATH`).
- Homebrew cask also available: `brew install --cask antigravity-cli`.

### npm package name

**There is no official npm package.** Antigravity CLI is a Go binary, not a Node package.
- ⚠️ **Name-collision warning:** the npm package `antigravity-cli` exists but is **an unrelated 2 KB third-party placeholder** — its own description is literally "Antigravity CLI - placeholder", its bin is named `kirox`, MIT, single maintainer, zero dependencies. Do **not** `npm i antigravity-cli` expecting Google's tool.

### Runtime requirements

- **Compiled in Go — self-contained static binary. No Node.js, no Python, no container, no runtime to install.** (Explicitly contrasted with Gemini CLI's Node dependency in Google's own "Choosing Antigravity or Gemini CLI" post.)
- **No GUI/display required.** It is a TUI; it explicitly targets "SSH/remote sessions, keyboard-first" use, and it detects SSH sessions during login. Headless mode exists: `agy -p "<prompt>"` (aliases `--print` / `--prompt`) for CI/scripting.
- Only prerequisite for install is `curl`.

### API key / auth config

Two supported paths:

1. **Interactive OAuth (default).** Authenticates via the **system keyring**, falling back to Google Sign-In. Locally it opens your default browser; over SSH it prints an authorization URL to open elsewhere. Sign out with `/logout`. Enterprise users connect a GCP project during onboarding.
2. **Gemini API key (headless/CI).** Add this to **`~/.gemini/antigravity-cli/settings.json`**:
   ```json
   { "modelProvider": "gemini" }
   ```
   then export in the same shell that launches `agy`:
   ```bash
   export GEMINI_API_KEY="your-key"
   # optional, for any Gemini-API-compatible endpoint:
   export GOOGLE_GEMINI_BASE_URL="https://example.com"
   ```
   **Critical gotcha, stated in Google's docs: setting `GEMINI_API_KEY` alone does nothing — `modelProvider` must be the exact lowercase string `"gemini"`.** The CLI does **not** read `.env` files and does **not** read `GOOGLE_API_KEY`. Model requests then go straight to the Gemini API with no account session.

- Other config paths: settings at `~/.gemini/antigravity-cli/settings.json`; MCP servers at `~/.gemini/antigravity-cli/mcp_config.json` (global) or `.agents/mcp_config.json` (workspace); global context at `~/.gemini/GEMINI.md`; workspace context at `.antigravity.md` > `GEMINI.md`, plus `AGENTS.md`.
- ⚠️ Open feature request (issue #632) asking for pure env-var token auth that bypasses keyring/interactive login, as legacy Gemini CLI did — implies non-interactive auth still leans on the keyring unless `modelProvider: "gemini"` is set.

### Linux ARM64 feasibility

**Yes — first-class, with published official binaries and a first-party-vendor install guide.**

Measured release-asset sizes for `v1.3.3`:

| Asset | Size |
|---|---|
| `agy_cli_linux_arm64.tar.gz` | **56.3 MB** |
| `agy_cli_linux_arm64_musl.tar.gz` | **54.4 MB** |
| `agy_cli_linux_x64.tar.gz` | 59.7 MB |
| `agy_cli_linux_x64_musl.tar.gz` | 59.8 MB |
| `agy_cli_mac_arm64.tar.gz` | 52.5 MB |
| `agy_cli_mac_x64.tar.gz` | 57.3 MB |
| `agy_cli_windows_arm64.zip` | 51.6 MB |
| `agy_cli_windows_x64.zip` | 56.1 MB |

- The installer auto-detects `aarch64`/`arm64` and picks the right build; **both glibc and static-musl variants ship**, so minimal containers/Alpine work.
- **Arm publishes its own install guide** (last updated 2026-09-03) explicitly covering "Arm Linux distributions" on Ubuntu/Debian/CentOS, plus Arm MCP server integration.
- Known rough edges (community-reported, not blocking): an early "[BUG] AGY crashes on arm64" report and a request for a *generic* AArch64 (ARMv8.0/v8.2) build for older server SoCs — the official build likely targets a newer ARMv8.x baseline. Also, a Termux/Android run reports 7 separate Android-specific issues.
- **Rough size: ~55 MB compressed, single binary, near-zero dependencies.** This is the largest-but-simplest footprint of the three tools.

### Notes / caveats

- **Closed source + subscription-quota politics.** The Gemini CLI handover was widely criticized; multiple users report much tighter shared quotas and heavier token burn than Gemini CLI. Treat model access/quotas as the main operational risk, not installation.
- Community reports claim **headless/print mode may be restricted to API-key users** rather than subscription accounts — **UNVERIFIED / conflicting with Google's headless docs**; flagged for confirmation.
- Googlebook/ChromeOS and "x86 not supported" constraints apply to the **Antigravity IDE**, not the CLI — do not conflate them.
- Interactions/telemetry data are used by default; opt-out exists in settings.

---

## 2. Hermes Agent

### Identity — **which "Hermes" this is**

The intended referent is almost certainly **Hermes Agent by Nous Research** (`github.com/NousResearch/hermes-agent`, docs at `hermes-agent.nousresearch.com`). Evidence it is the one meant when listed alongside Claude Code and Codex:

- It is the **#1 entry by stars** under "Terminal-native coding agents → Open Source" in the curated `awesome-cli-coding-agents` list, listed above OpenCode, Codex CLI and Gemini CLI.
- It is directly compared head-to-head with Claude Code and Codex in 2026 review roundups.
- **Nuance worth stating plainly:** Hermes is *not* primarily a coding agent. Its own docs say "It's **not** a coding copilot tethered to an IDE." It is a general autonomous, self-improving, multi-channel agent (Telegram/Discord/Slack/WhatsApp…) that **bundles skills to delegate coding to Claude Code, Codex and OpenCode**. So in practice people run it *with* Claude Code/Codex, not *instead of* them. If the user wants a direct Claude Code/Codex peer, Hermes is the wrong pick — but it is unambiguously the "Hermes" meant by that list.
- GitHub API reports ~**253k stars / 54.8k forks / 48k open issues**, MIT, primary language **Python**. (Treat the star count as API-reported at query time; it is anomalously high relative to peers.)

### Install

**No official npm or PyPI package. No Homebrew.** The official docs explicitly list these as **unsupported**: `uv tool install hermes-agent` / `pip install hermes-agent`, `brew install hermes-agent`, and AUR installs.

Supported methods:

```bash
# Linux / macOS / WSL2 (source install)
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash

# Windows native (PowerShell)
iex (irm https://hermes-agent.nousresearch.com/install.ps1)

# Docker
docker pull nousresearch/hermes-agent        # or :desktop

# Nix / NixOS — best-effort only, not officially supported
# Android aarch64 (Termux) — signed APT repo, prerelease
pkg install hermes-agent
```

Desktop bundles (macOS Apple-Silicon-only DMG; Windows signed MSIX) exist, but the CLI-only path above is the relevant one.

### npm package name

⚠️ The npm package **`hermes-agent` is an UNOFFICIAL third-party bridge**, not Nous Research's. Its own description says "**Unofficial npm bridge** for Hermes Agent"; maintainer `wyrtensi`, repo `github.com/wyrtensi/hermes-agent-npm`, MIT, 102.7 KB unpacked, bins `hermes` / `hermes-npm` / `hermes-agent`, `engines.node >=20`. It tracks upstream versions (0.21.6) but is **not** a Nous Research publication. Prefer the official `install.sh`.

### Runtime requirements

This is the **heaviest** of the three to install:

- **Python 3.14** for current first-party installs (`pyproject.toml` allows `>=3.11,<3.15` only so older interpreters can run the updater). The installer bootstraps a **verified `uv` pin** (an existing `uv` on `PATH` is ignored).
- A package manager the docs call **"PM"** provisions **pinned Python, Node.js, npm, ripgrep, and FFmpeg** from `pm/lock.json`. It deliberately does **not** adopt arbitrary system Node versions.
- Source builds **also install `agent-browser` with a pinned Chromium, plus the `cua-driver`** computer-use driver by default. Suppress with `--skip-browser` / `--skip-computer-use`.
- POSIX prerequisites: **git, curl, tar, sha256sum**. Windows can self-bootstrap Git.
- Native compiler + platform dev libraries may be needed for source builds; Chromium needs distro system libraries.
- **glibc caveat:** managed Node.js links `libatomic.so.1`, absent from minimal Debian/Ubuntu/RHEL images. The installer auto-installs `libatomic1` (apt/zypper), `libatomic` (dnf/yum/apk) or `gcc-libs` (pacman), or prints the exact command for a sudo-less admin to run.
- Install layout: `~/.hermes/hermes-agent/` + launcher `~/.local/bin/hermes`, data in `~/.hermes/`.
- No GPU required; Docker guidance cites 1 GB RAM minimum, 2–4 GB recommended, ≥2 GB with browser tools active.

### API key config

Everything lives in **`~/.hermes/`**:

```
~/.hermes/
├── config.yaml   # non-secret settings (model, terminal backend, toolsets…)
├── .env          # API keys and secrets  ← secrets go here
├── auth.json     # OAuth provider credentials (Nous Portal, etc.)
├── SOUL.md, memories/, skills/, cron/, sessions/, logs/
```

- Precedence: **CLI args → `config.yaml` → `.env` → built-in defaults.**
- `hermes config set UPPER_SNAKE_KEY value` auto-routes any `UPPER_SNAKE` name to `.env`; dotted keys go to `config.yaml`. E.g. `hermes config set OPENROUTER_API_KEY sk-or-...`.
- `${VAR}` and `${env:VAR}` substitution works inside `config.yaml`.
- Easiest path: **`hermes setup --portal`** — one OAuth covers Nous Portal (300+ models) plus the Tool Gateway (web search, image gen, TTS, browser). Alternatives: OpenRouter, OpenAI, Anthropic, or any endpoint.
- Config wizard: `hermes setup`, `hermes model`, `hermes tools`, `hermes gateway setup`. Diagnostics: `hermes doctor`.
- Terminal execution backend (where shell commands actually run) is configurable: `local | docker | ssh | modal | daytona | vercel_sandbox | singularity`.

### Linux ARM64 feasibility

**Yes — ARM64 is an explicitly Tier-1 supported platform.**

- Tier 1 matrix lists **Linux / WSL2 (x86_64, aarch64)** via `install.sh`, and **Docker Container (x86_64, aarch64)**.
- **Android/Termux aarch64** is Tier 2 via a signed APT repository (`pkg install hermes-agent`) — prerelease, only published from releases cut with `--include-termux`, and ships Python+Node+TUI.
- Non-aarch64 Android is explicitly unsupported.
- Watch-outs: native-source installs may need a cross/native toolchain; Chromium needs distro libs on ARM; and the `libatomic` requirement above bites minimal ARM images too (the installer handles it automatically or prints the command).
- **Rough size:** large and variable — a full stack of Python 3.14 + pinned Node/npm + ripgrep + FFmpeg + **pinned Chromium** + computer-use driver + source checkout + venvs and a per-install tool store. Expect **on the order of 1–3 GB on disk** rather than tens of MB. Use the Docker image or `--skip-browser` to trim it.

### Notes / caveats

- It's an **agent platform**, not a coding tool: 60+ built-in tools, MCP, a learning loop that self-creates skills, 20+ messaging gateways, cron, and subagent delegation. Coding is a delegated skill, not the core.
- Install ownership is per-method (source = git-managed updates; Desktop/Docker/Nix/Termux = package-owner updates). `hermes doctor` reports install provenance.
- Nix is **best-effort, not officially supported** — a common source of user confusion.
- macOS desktop bundles are **Apple Silicon only**; Intel macOS CLI still works.

---

## 3. Side-by-side

| | Antigravity CLI | Gemini CLI (predecessor) | Hermes Agent |
|---|---|---|---|
| Command | `agy` | `gemini` | `hermes` |
| npm package | **none official** (`antigravity-cli` on npm is a 3rd-party placeholder) | `@google/gemini-cli` (0.63.0, Apache-2.0) | **none official** (`hermes-agent` on npm is an unofficial bridge) |
| GitHub | [google-antigravity/antigravity-cli](https://github.com/google-antigravity/antigravity-cli) (closed source, no license) | [google-gemini/gemini-cli](https://github.com/google-gemini/gemini-cli) | [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) (MIT, Python) |
| Runtime | Go static binary, **no deps** | **Node >= 20** | Python 3.14 + pinned Node/ripgrep/FFmpeg/Chromium |
| GUI needed | **No** (TUI + `agy -p` headless) | No | No (optional Desktop app) |
| Auth | keyring OAuth / `GEMINI_API_KEY` + `modelProvider:"gemini"` | OAuth or `GEMINI_API_KEY` | `~/.hermes/.env` keys, or `hermes setup --portal` OAuth |
| Linux ARM64 | ✅ **56.3 MB native arm64 (musl too)**, Arm-published guide | ✅ | ✅ **Tier 1**, + Termux aarch64 Tier 2 |
| Rough size | **~55 MB**, zero deps | **~94 MB** unpacked (npm) | **~1–3 GB** (Python+Node+Chromium) |

## 4. Could NOT verify

- Direct extraction of `antigravity.google` official doc pages repeatedly failed (HTTP 422 from the fetch tool). Auth/headless/headline claims for Antigravity CLI are sourced from **search-result snippets quoting those pages**, plus Arm's official guide, the GitHub README, and third-party mirrors that reproduce the config steps verbatim — not from a successful first-party page load. Cross-checked across three independent sources.
- Whether Antigravity CLI headless mode works on a **consumer subscription** (vs API-key only) — community reports conflict with Google's docs.
- Hermes' exact on-disk footprint — no authoritative published number; the 1–3 GB estimate is inferred from the documented component list (Python + Node + FFmpeg + pinned Chromium), not measured.
- Whether generic ARMv8.0/v8.2 (pre-ARMv8.4-A) server CPUs work with the official `linux_arm64` build — only a feature request, no confirmation either way.