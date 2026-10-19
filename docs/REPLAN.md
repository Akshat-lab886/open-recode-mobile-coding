# DSH Mobile — Re-plan

Written after reading the reference implementation (`techjarves/Mobile-Harness`,
v1.0.7) and auditing our own source. Supersedes the "Universal Agent Deck"
plan, which produced the right tabs for the wrong architecture.

## 1. What we got wrong

**We inverted the architecture.** The user asked for a native mobile harness.
We built a WebView wrapper with seven native side panes.

The conversation — the surface a user looks at all day — is DSH's desktop web
UI in a `WebView`, reshaped by 613 lines of injected JavaScript
(`assets/dsh-mobile-shell.js`). The native composer is not a composer: it is a
relay. `CommandDispatcher.dispatch` is assigned *inside* `AgentWebView.kt` and
posts text into the page's JavaScript. The eight Deck tabs hang off the side of
a website.

The evidence that this was avoidable: `VisualGateway` already parses agent
stdout into structured events — `ChatDelta`, `ApprovalRequested`, `RawLine`.
That is exactly the substrate a native conversation UI is built from. We piped
those events into a 214-line `ui.html` instead of Compose.

### Compounding errors

1. **We hid the hard part.** Agent event parsing existed; we rendered it as HTML.
2. **Breadth over depth.** Eight tabs, but Terminal cannot hold a session
   (`ProcessBuilder("sh","-c",cmd)` per command), Diffs is a homegrown
   snapshot rather than git, Tasks is a static list. Eight shallow tabs
   photographs as progress and is not.
3. **We polished the wrong layer.** `@` mentions, voice input, image previews
   and file search are decorations on a web wrapper. Harness value is streaming
   output, tool-call visibility, approvals, session continuity.
4. **Verification was not real.** Work was reported verified when it was not.
   When it was finally checked on hardware, taps had been landing on the soft
   keyboard for several rounds because `uiautomator` reports app coordinates
   underneath the IME. A screenshot would have caught it immediately.
   **Rule going forward: a screenshot, never a dump alone.**
5. **We built for harnesses that cannot run here.** Device DNS is dead, so
   Codex/Claude/Antigravity cannot install or authenticate, yet we shipped a
   multi-harness picker and per-harness controls for them.

## 2. The bar, measured

| Concern | Mobile-Harness (bar) | DSH Mobile (now) |
| --- | --- | --- |
| Chat transcript | Native `MessageBubble` (PocketDevApp.kt:6918) | DSH web UI in a WebView |
| Markdown | `MarkdownText.kt` (489 lines): headers, lists, inline code, fenced code with **Copy** and **Run in terminal** | injected JavaScript |
| Approvals | `ToolRequest(toolName, explanation, affectedPaths, commandPreview, risk: RiskLevel)` rendered as a native `ApprovalCard` | WebView approval bridge |
| Agent output | `RuntimeEvent` sealed interface driving native UI | `GatewayEvent` rendered to HTML |
| Shell | Private PRoot Linux userspace; real bash/git | toybox `sh -c`, one-shot, no session |
| Agent bridges | Per-agent native: Claude 1,130 / DSH 1,061 / Antigravity 721 lines | WebView + gateway JS |
| Checkpoints | `WorkspaceCheckpoints.kt` (309 lines) | homegrown snapshot diff |
| Size | 26,964 Kotlin lines, 39 files | 13,940 Kotlin lines, 42 files |

The gap is not line count. It is that their agent surface is **native Compose
driven by a typed event stream**, and ours is **HTML driven by injected JS**.

## 3. What we keep

The runtime layer is not the problem and is worth preserving:

- `AgentRuntimeService` / `AgentRuntime` / `AgentInstaller` / `BootstrapManager`
- `VisualGateway` event parsing (`GatewayEvent`) — this becomes the native
  surface's data source, not a web page's
- `ShellRunner` (becomes the base of a persistent session)
- `WorkspaceTree`, `FilesPane` — the file browser is fine
- `WorkspaceDiff` / `LineDiff` — the LCS core is sound; the baseline model needs
  to become real checkpoints
- `ControlCenter` + per-harness Mode & Effort controls (genuinely built, and
  matches the user's explicit requirement)

## 4. The re-plan

### Phase 1 — Native conversation surface (the actual product)

Replace the WebView as the primary path. Driven by the existing
`GatewayEvent` stream:

- `ChatMessage` model: role, text, attachments, workedMillis, streaming flag
- `MessageBubble`: user right-aligned, agent left; selection container; timing
- `MarkdownText`: headers, lists, inline code, fenced blocks with Copy and
  Run-in-terminal — port the block model, do not invent one
- Tool-call cards: name, explanation, affected paths, command preview, risk
- `ApprovalCard`: native approve/deny, replacing the WebView bridge
- Streaming: append `ChatDelta` to the trailing agent message

**Exit criteria:** a full agent exchange — prompt, streamed reply with a code
block, a tool request, approve, result — readable and actionable natively, with
the WebView not involved. Screenshot-verified.

### Phase 1 — what it actually took (measured)

Recorded because the plan above assumed the event stream already existed. It did
not, and finding that out changed the work.

1. **`GatewayEventBus` is a dead bus.** `VisualGateway` is never constructed and
   `onGatewayEvent` is never called, so nothing has ever emitted on it. A native
   pane wired to that bus renders the user's own messages and never a reply.
   Subscribing alone would have shipped a beautiful empty theatre.

2. **The live stream is `gateway.js`'s SSE.** CLI agents boot *under*
   `assets/visual-gateway/gateway.js`, which owns the child's stdin/stdout and
   republishes parsed output on `GET /events` as
   `{type: echo|message|approval|raw|exit|error}`, with a 200-event backlog
   replayed to late subscribers. Reading that gives real agent output with **no
   change to the launch path** — the surgical seam, as intended.

3. **But it only exists for gateway-backed agents.** `dsh` and `opencode` boot
   their own servers and never touch `gateway.js`. `dsh` additionally requires a
   token from its printed entry URL (the bare origin answers 401) and speaks a
   **WebSocket** mux at `/api/remote.mux`, not SSE. Since `dsh` is the default
   agent, the native surface cannot be the only path yet.

   Consequence: `ChatSurfacePolicy` grants the native transcript only to agents
   that can fill it, and the header *states* the fallback reason rather than
   showing an empty pane. Silent fallback reads as a bug; stated fallback reads
   as a limit.

**Still open for native DSH:** a WebSocket mux client. `java.net.http`'s
WebSocket is API 33+ against a `minSdk` of 29, and there is no OkHttp in the
offline cache, so this needs a deliberate dependency decision, not a quick
addition.

**Verified so far:** the parsers (flat JSON, SSE framing, event mapping, echo
reconciliation) are unit-tested — 528 tests. **Not verified:** anything on
hardware this round; the tablet was not attached to the host, so the
screenshot gate is outstanding.

### Phase 2 — Real shell and real checkpoints

- Persistent shell session (streaming stdout/stderr, stdin, cwd persistence,
  long-running processes) instead of one-shot `sh -c`
- `WorkspaceCheckpoints`-equivalent: checkpoint before an agent turn, per-hunk
  accept/revert after
- PRoot Linux userspace is the bar's answer to "real commands and real git".
  It is a large bundled-rootfs decision, so it is a separate, explicit call —
  not something to smuggle into a polish round.

### Phase 3 — Session continuity

Native conversation list, resume, and clear semantics.

### Phase 4 — Honest harness support

Surface only harnesses that demonstrably run on this device. Make degradation
explicit in the UI rather than decorative.

### Phase 5 — Demote the WebView

Keep DSH web as an optional "Web UI" tab for features that only exist there.
It stops being the centre of the app.

## 5. Working rules for the rebuild

1. **Screenshot, not dump.** Every UI claim is backed by an image.
2. **No speculative polish on the web layer** while Phase 1 is incomplete.
3. **Depth before breadth.** One surface at the reference bar beats eight at
   ours.
4. **State the bar explicitly** before coding a surface, as section 2 does.
5. **A surface is done when it is used**, not when it compiles and has tests.
