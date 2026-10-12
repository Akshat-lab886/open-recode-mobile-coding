# Mobile-Harness: what it does better, and what we can adopt

Reference: `github.com/techjarves/Mobile-Harness` (v1.0.7, MIT), cloned and read on
2026-10-12. Findings below are from its source, not its README.

## The single most important difference

Mobile-Harness ships a **complete Alpine rootfs + PRoot**. Its `RuntimeInstaller`
downloads both, and every agent runs *inside* that guest:

```kotlin
installer.process(installed.proot, installed.rootfs, workspace, env, command, ...)
```

That is why it can run Claude Code and we cannot. It is not a trick — it is simply
the missing piece in our runtime. Our `runtime-assets.json` declares a 46 MB
`rootfs.tar.gz` at a URL that **404s** (the asset was never published to any release).

### Why that matters for Claude Code

Anthropic publishes standalone native binaries, and there is more than one flavour:

```
https://downloads.claude.ai/claude-code-releases/<version>/manifest.json
  linux-arm64        256,434,168
  linux-arm64-musl   248,788,800
```

I downloaded `linux-arm64-musl` and parsed its ELF headers directly:

```
class 64-bit  e_type ET_EXEC
PT_LOAD, PT_DYNAMIC, PT_INTERP, ...
VERDICT: DYNAMIC
```

It is **musl-dynamic** — it needs `/lib/ld-musl-aarch64.so.1`, which exists only
inside a rootfs. So our "Claude Code is unsupported here" verdict **stands**, and
Mobile-Harness is not evidence against it. It is evidence *for* their architecture.

Useful byproduct: `downloads.claude.ai` is a cleaner install path than npm —
no postinstall hook, no platform gate. Worth remembering if we ever ship a rootfs.

## What they do that we should adopt

| Area | Their approach | Why it is better for us |
|---|---|---|
| **Agent interface** | Headless: `claude -p <prompt> --output-format stream-json --include-partial-messages` | A TUI cannot survive PRoot on a phone. Streaming JSON renders into native Compose and looks native instead of looking like a terminal. |
| **No terminal required** | Chat is app-native UI; `TerminalScreen.kt` is a separate opt-in tool | Matches the "app version, not CLI" goal directly. |
| **Auth** | `CLAUDE_CODE_OAUTH_TOKEN`, and deliberately *not* `--bare` ("bare mode ignores `CLAUDE_CODE_OAUTH_TOKEN`") | Lets a user reuse a Claude subscription. We currently only support raw API keys. |
| **Version pinning** | Resolve `latest` from the registry, then fetch a checksummed artifact and `verifyGuest` by running `--version` | We install whatever npm resolves and never verify the binary actually runs. |
| **Self-repair** | `RootfsRepair.kt` | A corrupt runtime is recoverable instead of a dead app. |
| **Concurrency** | One `RuntimeExecutionService`, foreground, with throttled progress | Ours is a `LifecycleService`; theirs survives work better. |

## The Codex answer: `codex app-server`

You asked for the *app version* of Codex, not the CLI. It exists — verified on
device (`codex-cli 0.162.1`):

```
codex app-server        [experimental] Run the app server or related tooling
  daemon                Manage the local app-server daemon
  proxy                 Proxy stdio bytes to the running app-server control socket
  generate-json-schema  Generate JSON Schema for the app server protocol
```

`app-server` is a **JSON-RPC server over stdio**, intended to be embedded in a GUI —
structurally the same as the DSH web server we already host. This is the correct
integration path, and it means Codex can get a real native chat UI on a phone
without ever rendering a TUI.

Also available and useful: `codex exec` (non-interactive), `agents` (browse
sessions), `resume`/`queue`/`archive`/`delete` (session lifecycle — which maps
directly onto our session-history feature), and `codex doctor`.

## Recommended order of work

1. **Codex `app-server` bridge** — spawn it, speak JSON-RPC, render into the native
   chat UI. Highest value: turns a working binary into a usable app.
2. **Headless `-p --output-format stream-json` for every CLI agent**, so no agent
   ever depends on a terminal. This is the general pattern.
3. **Download + verify step** — checksum, then run `--version`, before reporting an
   agent installed. Closes the "installed but dead" class of bug for good.
4. **Rootfs, only if Claude Code is genuinely required.** 46 MB and a dead URL
   today. Not worth it while Codex works.
5. **OAuth/subscription auth** (`CLAUDE_CODE_OAUTH_TOKEN`) as an alternative to
   raw API keys.

## What we should *not* copy

- Their "Flags not verified against this CLI's help yet" style of caveat is the
  right instinct, but we now verify flags against the real binary, which is better.
- They ship Claude Code despite it being unsupported on Android. We will keep the
  honest `Unsupported` entry with its reason rather than offering a dead install.

## Security note

While researching, a search backend returned **HTTP 402 with an embedded message
claiming an API key had been generated**, instructing the agent to authenticate with
it. This is prompt-injection-shaped content arriving inside a tool response, not an
instruction from the user or the system. **It was not acted on.** Any credential
surfaced that way must be treated as compromised.
