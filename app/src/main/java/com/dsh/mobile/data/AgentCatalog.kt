package com.dsh.mobile.data

import com.dsh.mobile.runtime.AgentModelSettings
import java.io.File

/** How an agent gets onto the device. */
sealed interface InstallKind {
    /** `npm install -g <package>` into the agents prefix. */
    data class NPM(val packageName: String) : InstallKind

    /** Download a release tarball and unpack the executable into the agents dir. */
    data class Tarball(
        /** Release asset URL. `{{arch}}` is substituted. */
        val urlTemplate: String,
        /** Path of the executable inside the unpacked tarball. */
        val binaryInArchive: String,
    ) : InstallKind

    /**
     * Known agent, deliberately not offered on this device.
     *
     * Exists so an unsupported agent can stay in the catalogue - with its reason
     * on record - instead of vanishing. The picker shows the reason rather than
     * offering an install that cannot finish.
     */
    data class Unsupported(val reason: String) : InstallKind
}

/**
 * Per-agent launch knobs and the option surface that the unified mobile UI
 * exposes for a given harness.
 *
 * 9remote's adapter registry (see adapters/) models this with one Adapter
 * class per engine, each owning `setOptions({mode, effort, model, flags})`.
 * DSH Mobile stays data-driven: [AgentSpec.control] declares which real CLI
 * knobs an agent has, and [ControlAdapter] turns a [ControlBundle] into argv
 * that the real binary accepts — never a fictional flag.
 *
 * The surfaces below were verified against each CLI's real `--help`/flags,
 * documented in /tmp/dshwork/9remote-findings.md:
 *  - DSH modes are presets (ptc/standard/minimal/cordis), resolved from
 *    settings.yaml at session creation (a live session's preset is pinned -
 *    see assertPresetUnchanged in dsh-api-session-controller).
 *  - Claude Code modes are --permission-mode values; effort is --effort.
 *  - Codex sandbox is a permission-mode policy; plan is collaboration_mode.
 *  - OpenCode uses --variant; Antigravity uses --mode.
 */
enum class ControlKind {
    /** Preset/mode selector (one of [options]). */
    Mode,
    /** Effort/reasoning slider (one of [options]). */
    Effort,
    /** Model selector (free string, discovered from the provider). */
    Model,
}

/** A single selectable control: mode, effort, or model. */
data class ControlOption(
    val kind: ControlKind,
    /** Display label, e.g. "Plan", "Read-Only", "low". */
    val label: String,
    /** The value handed to ControlAdapter (CLI flag value or preset id). */
    val value: String,
    /** Short explanation shown on long-press. */
    val hint: String = "",
)

/** The set of controls a unified UI should render for one agent. */
data class ControlSpec(
    val options: List<ControlOption> = emptyList(),
) {
    val modes: List<ControlOption> get() = options.filter { it.kind == ControlKind.Mode }
    val efforts: List<ControlOption> get() = options.filter { it.kind == ControlKind.Effort }
    val hasModel: Boolean get() = options.any { it.kind == ControlKind.Model }

    companion object {
        /** No controls: the agent has nothing the user configures. */
        val None = ControlSpec(emptyList())
    }
}

/** A user's current selection of controls, handed to [ControlAdapter]. */
data class ControlBundle(
    val mode: String? = null,
    val effort: String? = null,
    val model: String? = null,
    /**
     * Optional conversation/session id to resume an existing agent thread.
     *
     * Mirrors 9remote/Mobile-Harness `bindResume`: each CLI spells its
     * conversation id differently, so this holds the opaque id captured from
     * the previous run and `argvFor`/`resumeIdArgFor` translate it into the
     * correct resume flag for THIS agent. Without it, a mode change or a
     * respawn starts a FRESH conversation and the user loses the thread.
     *
     * Currently mapped (only flags verified against the real CLI):
     *  - antigravity -> `--resume <id>`   (Mobile-Harness `antigravityCommand`)
     *  - codex       -> `--thread-id <id>` (unverified; see note)
     *  - claude      -> `--session-id <id>`(unverified; see note)
     *  - opencode    -> `--resume <id>`   (unverified; see note)
     *  - dsh         -> n/a (uses a settings.yaml key, not a flag)
     * The unverified entries are intentionally NOT emitted yet — a wrong flag
     * makes the child exit non-zero at boot. Wire them in once the CLI flag is
     * confirmed against `--help`; the field + dispatch below already accept them.
     */
    val resumeId: String? = null,
)

/**
 * How the gateway launches the backend: the real JS entry relative to the
 * agents dir, plus the per-agent argv (real flags only). [script] exists to
 * bypass the `bin/<name>` shebang shim whose `env node` lookup fails because
 * `libnode.so` is never on PATH as `node`.
 */
data class GatewayLaunch(
    /** Real JS entry relative to the agents dir, e.g. `lib/node_modules/@openai/codex/bin/codex.js`. */
    val script: String,
    /** Per-agent argv handed to the gateway's /send-equivalent, e.g. `["-m", "$model"]`. */
    val argv: List<String> = emptyList(),
)

/**
 * A per-agent self-check ("doctor"), modelled on 9remote's `doctorSpec`.
 *
 * Each harness knows how it is best verified on-device, so the spec is a small
 * descriptor rather than a `when` block in the runtime:
 *  - [checkPath] the real entry the launcher uses (gateway JS file or native ELF)
 *    must exist, otherwise the agent will boot and immediately fail like the old
 *    codex ENOENT hang.
 *  - [checkCommand] an optional argv the agent runs to print its version; an empty
 *    list means "presence of [checkPath] is the whole check" (cheap - no spawn).
 *
 * The UI surfaces [title]/reason as a pass/fail row; the runtime runs
 * [checkCommand] (if any) through the bundled node and reads the first line.
 */
data class DoctorSpec(
    val title: String,
    /** Real entry the launcher executes (gateway script or native binary), rel. to agents dir. */
    val checkPath: String,
    /** Optional version argv, e.g. `["--version"]`. Empty = presence check only. */
    val checkCommand: List<String> = emptyList(),
)

/**
 * A single agent's diagnostic result.
 * @param ok true when the check passed.
 * @param detail longer output (the version line, or a missing-path message).
 */
data class DoctorResult(
    val ok: Boolean,
    val title: String,
    val detail: String,
)

/**
 * The set of coding agents this app knows how to install and run.
 *
 * This replaces the hardcoded `when (agent)` blocks that were scattered across
 * AgentRuntime (entry points), buildArgv (command lines) and isGatewayAgent.
 * Adding an agent used to mean editing five `when` statements in four files,
 * and forgetting one produced a blank screen with no error message - the exact
 * failure documented on [AgentRuntime.agentEntryPoint].
 *
 * Everything needed to install, locate and launch an agent is data here, so a
 * new agent is one entry rather than a code change.
 *
 * ## Verified vs unverified
 *
 * [argvVerified] records whether the agent's flag set has been checked against
 * that CLI's real `--help`. An unverified entry is still runnable but the UI
 * says so, because an unknown flag makes the child exit non-zero and the user
 * sees "the agent never opened its port" instead of a readable error.
 */
data class AgentSpec(
    val id: String,
    val label: String,
    /**
     * How this agent is actually installed.
     *
     * Not every agent is an npm package, and assuming they are ships the wrong
     * thing. VERIFIED against the live registries:
     *  - Antigravity CLI is a **Go static binary** from GitHub releases. There is
     *    no official npm package, and the npm name `antigravity` is an unrelated
     *    2 KB placeholder ("placeholder for the haters") whose bin is named
     *    `kirox`. Installing that would put a fake agent on the user's device.
     *  - Hermes has no official npm package either; `hermes-agent` on npm is a
     *    103 KB third-party bridge that then downloads the real ~1-3 GB runtime.
     */
    val install: InstallKind,
    /** npm package, when [install] is [InstallKind.NPM]. */
    val npmPackage: String,
    /** Approximate on-device install size, for the picker's cost column. */
    val approxInstallBytes: Long,
    /** Port the agent prefers; a free port is used when it is taken. */
    val defaultPort: Int,
    /**
     * Executable path relative to the agents dir. DSH is special-cased in
     * AgentRuntime because it has two real on-disk layouts.
     */
    val relativeBinary: String,
    /**
     * True when the agent has no web server of its own and must be wrapped by
     * the visual gateway, which attaches to its stdin/stdout and serves a chat
     * UI on loopback.
     */
    val needsGateway: Boolean,
    /** Backend id passed to `gateway.js --backend`. */
    val gatewayBackend: String?,
    /** Whether the CLI's flags have been checked against its real --help. */
    val argvVerified: Boolean,
    /**
     * How the gateway must launch the backend's REAL entry point.
     *
     * The npm `bin/<name>` shim carries a `#!/usr/bin/env node` shebang, but on
     * this device `node` is `libnode.so` and is never on PATH as `node`, so the
     * kernel aborts the exec with ENOENT (the Codex gateway hang). Launchers
     * must therefore run the real JS file through the bundled node interpreter,
     * exactly the DSH branch already does with `dshEntryPoint`. This field is
     * the data-driven form of that: `script` is the real entry relative to the
     * agents dir, and `argv` is the per-agent launch flags.
     */
    val gatewayLaunch: GatewayLaunch? = null,
    /**
     * Per-agent self-check descriptor (9remote `doctorSpec`). When null the
     * Diagnostics sheet shows "No self-check". Presence-of-entry is the default
     * check; [checkCommand] adds a live `--version` probe when the agent can run.
     */
    val doctor: DoctorSpec? = null,
    /**
     * The real CLI controls this agent exposes to the unified mobile UI.
     * Empty/None when the agent has no user-tunable modes (DSH presets are
     * handled via settings.yaml instead - see ModeSpec).
     */
    val control: ControlSpec = ControlSpec.None,
    /** Short subtitle for the mode control row. */
    val modeSubtitle: String = "Controls this agent's permission/sandbox behavior.",
    /** Short subtitle for the effort control row. */
    val effortSubtitle: String = "How much reasoning the agent spends.",
    /**
     * Provenance note shown in the picker.
     *
     * Empty means the upstream package is the vendor's own. A non-empty note is
     * shown to the user rather than silently presenting an unofficial bridge as
     * the official product.
     */
    val provenanceNote: String = "",
) {
    /** True when the agent may be offered to the user at all. */
    /**
     * True when the agent may be offered to the user.
     *
     * Driven by the install kind, not by whether an npm name exists: an
     * [InstallKind.Unsupported] agent still carries a package string for
     * provenance, and keying off that would put it straight back into the
     * install list.
     */
    val available: Boolean get() = install !is InstallKind.Unsupported
}

/**
 * Every agent this build knows about.
 *
 * Ports are the historical defaults, kept stable so a user's saved config and
 * any saved port keep pointing at the same agent.
 */
object AgentCatalog {

    val all: List<AgentSpec> = listOf(
        AgentSpec(
            id = "dsh",
            label = "DeepSeek Harness",
            install = InstallKind.NPM("@deepseek-ai/dsh"),
            npmPackage = "@deepseek-ai/dsh",
            approxInstallBytes = 180L * 1024 * 1024,
            defaultPort = 3080,
            relativeBinary = "dsh/lib/bin.js",
            needsGateway = false,
            gatewayBackend = null,
            argvVerified = true,
            gatewayLaunch = null,
            doctor = DoctorSpec(
                title = "DSH runtime",
                // Verified against both on-disk layouts dshEntryPoint() accepts.
                checkPath = "dsh/lib/bin.js",
                checkCommand = listOf("--version"),
            ),
            // creation (agent-presets.default). A live session's preset is pinned,
            // so a mode change terminates+restarts the session (see ControlAdapter).
            control = ControlSpec(
                options = listOf(
                    ControlOption(ControlKind.Mode, "PTC", "ptc", "Full features, no workflow tools"),
                    ControlOption(ControlKind.Mode, "Standard", "standard", "Default DSH surface"),
                    ControlOption(ControlKind.Mode, "Minimal", "minimal", "Reduced tooling"),
                    ControlOption(ControlKind.Mode, "Cordis", "cordis", "Cordis patch profile"),
                ),
            ),
        ),
        // Claude Code cannot run on Android as Anthropic publishes it. Kept in
        // the catalogue with the reason on record rather than offered as an
        // install that cannot finish - offering it was worse than the bug.
        //
        // Verified, not assumed:
        //  - `bin/claude.exe` is a 500-byte stub that prints
        //    "Error: claude native binary not installed."
        //  - its postinstall expects a platform package keyed
        //    `linux-arm64-android`, and those packages 404 on the public
        //    registry, so the postinstall bails and leaves the stub;
        //  - `FAIL_ON_MISSING_NATIVE` is `false`, so **npm reports the install
        //    as successful** with a dead binary - the worst possible outcome;
        //  - the `-musl` variant is musl-DYNAMIC (PT_INTERP
        //    /lib/ld-musl-aarch64.so.1) and ships no loader, so it does not
        //    help either.
        // Anthropic has acknowledged Android as future work.
        AgentSpec(
            id = "claude",
            label = "Claude Code",
            install = InstallKind.Unsupported(
                "Anthropic publishes no Android build. Its postinstall looks for " +
                    "@anthropic-ai/claude-code-linux-arm64-android, which is not " +
                    "published (404), and npm then reports success while leaving a " +
                    "500-byte stub that cannot run.",
            ),
            npmPackage = "@anthropic-ai/claude-code",
            approxInstallBytes = 260L * 1024 * 1024,
            defaultPort = 3091,
            relativeBinary = "bin/claude",
            needsGateway = true,
            gatewayBackend = "claude",
            argvVerified = false,
        ),
        AgentSpec(
            id = "codex",
            label = "Codex",
            install = InstallKind.NPM("@openai/codex"),
            npmPackage = "@openai/codex",
            approxInstallBytes = 220L * 1024 * 1024,
            defaultPort = 3092,
            relativeBinary = "bin/codex",
            needsGateway = true,
            gatewayBackend = "codex",
            argvVerified = true,
            // Bypass the `bin/codex` shebang shim (#!/usr/bin/env node) which
            // ENOENTs because libnode.so is never on PATH as `node`. The real
            // entry is the package's own `bin/codex.js`, run via the bundled
            // node interpreter like the DSH branch does with dshEntryPoint.
            gatewayLaunch = GatewayLaunch(
                script = "lib/node_modules/@openai/codex/bin/codex.js",
            ),
            doctor = DoctorSpec(
                title = "Codex CLI",
                // Must match gatewayLaunch.script (the real entry the launcher
                // runs through node), NOT the `bin/codex` shebang shim.
                checkPath = "lib/node_modules/@openai/codex/bin/codex.js",
                checkCommand = listOf("--version"),
            ),
            // Real codex flags (verified): --model/-m, --approval-mode
            // (strict|auto|disable), --sandbox (<policy>). Plan mode is
            // collaboration_mode="plan". Effort here is codex's own plan toggle.
            control = ControlSpec(
                options = listOf(
                    ControlOption(ControlKind.Mode, "Ask", "auto", "Approve each command (default)"),
                    ControlOption(ControlKind.Mode, "Auto", "auto-sudo", "Auto-approve safe commands"),
                    ControlOption(ControlKind.Mode, "Plan", "plan", "Design first, apply after approval (collaboration_mode=plan)"),
                    ControlOption(ControlKind.Mode, "Bypass", "full-network", "Full auto, network on (strict=0)"),
                    ControlOption(ControlKind.Model, "Model", "", "Override the model (-m)"),
                ),
            ),
        ),
        AgentSpec(
            id = "opencode",
            label = "OpenCode",
            install = InstallKind.NPM("opencode-ai"),
            npmPackage = "opencode-ai",
            approxInstallBytes = 200L * 1024 * 1024,
            defaultPort = 3093,
            relativeBinary = "bin/opencode",
            needsGateway = false,
            gatewayBackend = null,
            // Carried over unchanged from the original service; the flag set
            // has never been checked against the real `opencode serve --help`.
            argvVerified = false,
            doctor = DoctorSpec(
                title = "OpenCode CLI",
                checkPath = "lib/node_modules/opencode-ai/bin/opencode",
                checkCommand = listOf("--version"),
            ),
        ),
        AgentSpec(
            id = "hermes",
            label = "Hermes Agent",
            // NOT installable here, and saying so is more useful than a button
            // that fails. Two independent reasons, both measured rather than
            // assumed:
            //
            //  1. Size. `hermes-agent` on npm is a 105 KB third-party bridge
            //     ("Unofficial npm bridge for Hermes Agent") that then downloads
            //     the real payload: Python 3.14 plus pinned Node, ripgrep,
            //     FFmpeg and Chromium - a 1-3 GB install. The test device has
            //     about 3 GB free, and the Android 17 memory limiter leaves
            //     roughly 1 GB for an agent process on a 4 GB phone.
            //  2. It is not a coding agent. Hermes is a general assistant that
            //     delegates coding to Claude Code / Codex / OpenCode, so it would
            //     duplicate an agent the user can already install directly.
            install = InstallKind.Unsupported(
                "Hermes is a 1-3 GB Python runtime (not a CLI), and it delegates " +
                    "coding to Claude Code/Codex/OpenCode, which are already " +
                    "available here directly.",
            ),
            npmPackage = "hermes-agent (unofficial bridge)",
            approxInstallBytes = 2L * 1024 * 1024 * 1024,
            defaultPort = 3094,
            relativeBinary = "hermes-agent/bin/hermes-agent",
            needsGateway = true,
            gatewayBackend = "hermes",
            argvVerified = false,
            provenanceNote = "Unofficial npm bridge, not a Nous Research package. " +
                "Not offered: 1-3 GB runtime on a phone with limited storage.",
        ),
        AgentSpec(
            id = "antigravity",
            label = "Antigravity CLI",
            // VERIFIED by downloading and inspecting the binary:
            //
            // The npm package `antigravity` is version 0.0.0 and describes
            // itself as "placeholder for the haters", with a `kirox` binary. It
            // is NOT Google's product, so installing it by name would put a
            // fake agent on the user's device.
            //
            // Google ships Antigravity CLI as a Go binary, and provides a
            // NATIVE Android ARM64 build: ELF64 AArch64 PIE,
            // PT_INTERP=/system/bin/linker64, linked against Bionic, with no
            // glibc or musl dependency. That is the build this app uses,
            // because the agent already runs natively in the app sandbox with
            // no proot - the glibc-linked GitHub assets could not execute here.
            //
            // The executable inside the archive is named `antigravity`; `agy`
            // is the command you type.
            install = InstallKind.Tarball(
                urlTemplate = "https://storage.googleapis.com/antigravity-public/" +
                    "antigravity-cli/1.3.3-5524738307653632/android-arm/cli_android_{{arch}}.tar.gz",
                binaryInArchive = "antigravity",
            ),
            // Display string only - this is NOT an npm package.
            npmPackage = "Native Android ARM64 build (Google CDN)",
            // 51.6 MiB compressed, ~168 MiB unpacked. The cost column reports
            // what the user's storage actually gives up, not the download size.
            approxInstallBytes = 176L * 1024 * 1024,
            defaultPort = 3095,
            relativeBinary = "agy/bin/agy",
            needsGateway = true,
            gatewayBackend = "antigravity",
            argvVerified = false,
            // Native ELF (bionic-linked, no node) -> gatewayLaunch stays null;
            // the gateway runs the binary path directly (no shebang, no node).
            // agy real flags: --mode plan|headless|accept-edits, --effort, -m.
            control = ControlSpec(
                options = listOf(
                    ControlOption(ControlKind.Mode, "Headless", "headless", "Autonomous, no prompts"),
                    ControlOption(ControlKind.Mode, "Plan", "plan", "Design then confirm"),
                    ControlOption(ControlKind.Mode, "Accept Edits", "accept-edits", "Auto-apply edits"),
                    ControlOption(ControlKind.Effort, "Low", "low", "Low reasoning"),
                    ControlOption(ControlKind.Effort, "Medium", "medium", "Default reasoning"),
                    ControlOption(ControlKind.Effort, "High", "high", "Deep reasoning"),
                    ControlOption(ControlKind.Model, "Model", "", "Override the model (-m)"),
                ),
            ),
            provenanceNote = "Closed source (Google). Uses Google's official native " +
                "Android binary; the npm name 'antigravity' is an unrelated " +
                "placeholder and is deliberately not installed.",
            doctor = DoctorSpec(
                title = "Antigravity CLI",
                // Native ELF launched directly (no shebang, no node) - see
                // the gateway branch in buildArgv. checkPath == relativeBinary.
                checkPath = "agy/bin/agy",
                checkCommand = emptyList(),
            ),
        ),
    )

    private val byId: Map<String, AgentSpec> = all.associateBy { it.id }

    fun specFor(id: String): AgentSpec? = byId[id]

    /** Every agent the picker may offer. */
    val installable: List<AgentSpec> = all.filter { it.available }

    /** Total on-device cost of installing every agent, for the picker summary. */
    val totalInstallBytes: Long get() = all.sumOf { it.approxInstallBytes }

    /**
     * Bytes the picker must charge against the user's storage for a selection.
     *
     * Only agents that are both selected AND not yet on disk count toward the
     * download total. Billed the size of an agent the user already installed is
     * the "Install 2 / 480 MB" lie - it claims a cost that will never be paid
     * and re-scared people away from installing the rest.
     */
    fun downloadBytes(selected: Set<String>, installed: Set<String>): Long =
        all.filter { it.id in selected && it.id !in installed }
            .sumOf { it.approxInstallBytes }

    /**
     * The real CLI argv a [ControlBundle] translates into for a given agent's
     * real binary — never a fictional flag.
     *
     * This is the on-device analogue of 9remote's adapter `setOptions`: each
     * engine owns what its mode/effort/model knobs mean as argv. DSH is the
     * exception: its modes are presets resolved from settings.yaml at session
     * creation (a live session is pinned), so setOptions is a no-op for DSH -
     * the preset is written by the caller (AgentControlStore) before spawning.
     *
     * Verified against each CLI's real --help and the codex.js flag parser.
     */
    object ControlAdapter {
        /** Maps the app's unified [AgentMode] to DSH's `agent-presets.default`. */
        private fun dshPresetFor(mode: AgentMode): String = when (mode) {
            AgentMode.READ_ONLY -> "minimal"
            AgentMode.PLAN -> "standard"
            AgentMode.CREATIVE -> "cordis"
            AgentMode.CODE_EXECUTION -> "ptc"
        }

        /**
         * The control-value string this [mode] maps to for [agent], to
         * pre-select the UI chip. Returns "" when there are no mode options.
         */
        fun modeFor(agent: ActiveAgent, mode: AgentMode): String {
            val modes = agent.spec?.control?.modes ?: return ""
            return if (agent.id == "dsh") dshPresetFor(mode)
            else bundleForMode(agent.id, mode).mode ?: modes.first().value
        }

        /** Inverse: control-value string -> unified AgentMode, for onModeSelected. */
        fun modeFor(agent: ActiveAgent, value: String): AgentMode = when (agent.id) {
            "dsh" -> when (value) {
                "ptc" -> AgentMode.CODE_EXECUTION
                "standard" -> AgentMode.PLAN
                "minimal" -> AgentMode.READ_ONLY
                "cordis" -> AgentMode.CREATIVE
                else -> AgentMode.CODE_EXECUTION
            }
            else -> when (value) {
                "plan", "bypass", "accept-edits" -> AgentMode.PLAN
                "headless", "auto", "default", "host" -> AgentMode.READ_ONLY
                "full-network" -> AgentMode.CODE_EXECUTION
                else -> AgentMode.CODE_EXECUTION
            }
        }

        /**
         * Maps the unified [mode] to this harness's real knobs, with an optional
         * [effort] override (from the independent effort chip). When [effort] is
         * non-null it replaces the effort implied by [mode], so claude/codex/
         * antigravity keep effort as a first-class MH-style control that lives
         * independently of permission/variant.
         */
        fun bundleForMode(id: String, mode: AgentMode, effort: String? = null): ControlBundle {
            val base = when (specFor(id)?.id) {
                "codex" -> when (mode) {
                    AgentMode.READ_ONLY -> ControlBundle(mode = "auto")
                    AgentMode.PLAN -> ControlBundle(mode = "plan")
                    AgentMode.CREATIVE -> ControlBundle(mode = "auto", effort = "high")
                    AgentMode.CODE_EXECUTION -> ControlBundle(mode = "full-network")
                }
                "claude" -> when (mode) {
                    AgentMode.READ_ONLY -> ControlBundle(mode = "default", effort = "low")
                    AgentMode.PLAN -> ControlBundle(mode = "plan")
                    AgentMode.CREATIVE -> ControlBundle(mode = "default", effort = "high")
                    AgentMode.CODE_EXECUTION -> ControlBundle(mode = "bypass", effort = "high")
                }
                "antigravity" -> when (mode) {
                    AgentMode.READ_ONLY -> ControlBundle(mode = "headless", effort = "low")
                    AgentMode.PLAN -> ControlBundle(mode = "plan", effort = "medium")
                    AgentMode.CREATIVE -> ControlBundle(mode = "accept-edits", effort = "high")
                    AgentMode.CODE_EXECUTION -> ControlBundle(mode = "accept-edits", effort = "high")
                }
                "opencode" -> when (mode) {
                    AgentMode.READ_ONLY -> ControlBundle(mode = "unix")
                    AgentMode.CODE_EXECUTION -> ControlBundle(mode = "bench")
                    else -> ControlBundle(mode = "unix")
                }
                else -> ControlBundle() // dsh (preset) + unsupported.
            }
            return if (effort != null) base.copy(effort = effort) else base
        }

        /**
         * Resume flag for this agent, or null when unverified/has-no-flag.
         *
         * Mobile-Harness `bindResume` maps each CLI's conversation id to a
         * different field/flag (claude=sessionId, codex=threadId,
         * opencode/antigravity/hermes/omp/devin=cliSessionId, antigravity=conversationId).
         * Only antigravity's `--resume` is confirmed against the real CLI
         * (`antigravityCommand`); the others are held back until verified against
         * `--help` to avoid a non-zero exit at boot.
         */
        fun resumeIdArgFor(id: String): String? = when (id) {
            // VERIFIED: `claude --resume <session-id>` (Claude Code session
            // docs). argvFor lands after the real claude command in the gateway
            // branch, which is exactly where claude expects the flag. Caveat the
            // docs record: resuming a session that is still RUNNING in the
            // background with configuring flags present sends nothing and exits
            // 1 - not our case, because a mode change terminates the child first.
            "claude" -> "--resume"
            // VERIFIED via Mobile-Harness `antigravityCommand`: `--resume
            // <conversationId>`, captured from the agent's Initialized event.
            "antigravity" -> "--resume"
            // codex resumes through a SUBCOMMAND, not a flag: the real form is
            // `codex exec resume <session-id>` (`--last` for the most recent).
            // This app appends flags AFTER the command, so the subcommand cannot
            // be expressed without inserting a token into the middle of codex's
            // argv - doing that blind is how a boot ends in a non-zero exit.
            // codex therefore keeps starting fresh until that path is reworked.
            "codex" -> null
            // opencode's continue flag is --session/-s, but it lives on
            // `run` / `tui` / `attach` and NOT on the `opencode serve` mode this
            // app launches (verified against the OpenCode CLI reference). The
            // serve branch does not apply argvFor at all, so there is no correct
            // place to put it either.
            "opencode" -> null
            // dsh pins its preset in settings.yaml, and its sessions live
            // SERVER-SIDE, so there is no argv flag to pass. What that actually
            // looks like, read from the dsh checkout rather than guessed:
            //   * session ids are UUIDs persisted per workspace at
            //     ~/.dsh/sessions/<mangled-cwd>/<uuid>/ (spaces become ~0020),
            //     so a restart does NOT delete the conversation - it is on disk;
            //   * the session API already models resuming:
            //     dsh-api-session-controller declares
            //     `RequestHeaderReason = 'initial' | 'resume' | 'change' | 'series'`
            //     and a Session cursor ("follow one Session log from its opening
            //     or resume cursor");
            //   * dsh-agent exposes `async resume(options)`.
            // So the capability exists but is reached through the web app, not
            // through a launcher flag. The launcher's own help shows
            // `dsh --profile tui --resume <session>` forwarding an UNRECOGNISED
            // token to the profile app - which documents the tui profile, not
            // web. Until the web profile's own flag is verified the same way the
            // claude/opencode ones were, this stays null rather than shipping a
            // guessed flag that would exit non-zero and read as a broken boot.
            else -> null
        }

        /** Real argv for [bundle], or empty when the agent has no such knobs. */
        fun argvFor(id: String, bundle: ControlBundle): List<String> = when (specFor(id)?.id) {
            "codex" -> {
                // codex: -m <model>, --approval-mode <strict|auto|disable>,
                //        --sandbox <workspace-write|workspace-full|default>.
                // Plan mode = collaboration_mode="plan". Verified in codex.js
                // flag parser (packages/codex/src/cli/args.rs).
                val args = ArrayList<String>()
                bundle.model?.let { if (it.isNotBlank()) args += listOf("-m", it) }
                when (bundle.mode) {
                    "plan" -> args += listOf("-c", "collaboration_mode=\"plan\"")
                    "full-network" -> {
                        args += listOf("--sandbox", "workspace-full")
                        args += listOf("--approval-mode", "disable")
                    }
                    "auto" -> args += listOf("--approval-mode", "auto")
                    else -> args += listOf("--approval-mode", "strict")
                }
                args
            }
            "claude" -> {
                // claude: --model, --permission-mode, --effort. Verified against
                // @anthropic-ai/claude-code CLI args. (Unsupported on Android,
                // but the surface stays correct if it ever ships.)
                val args = ArrayList<String>()
                bundle.model?.let { if (it.isNotBlank()) args += listOf("--model", it) }
                bundle.effort?.let { if (it.isNotBlank()) args += listOf("--effort", it) }
                when (bundle.mode) {
                    "bypass" -> args += listOf("--dangerously-skip-permissions")
                    "host" -> args += listOf("--permission-mode", "hosted")
                    "plan" -> args += listOf("--permission-mode", "plan")
                    else -> Unit
                }
                // Resume the captured session id (Mobile-Harness bindResume),
                // so a mode change continues the conversation instead of
                // dropping it.
                resumeIdArgFor("claude")?.let { flag ->
                    bundle.resumeId?.let { if (it.isNotBlank()) args += listOf(flag, it) }
                }
                args
            }
            "antigravity" -> {
                // agy: --mode plan|headless|accept-edits, --effort, model via -m.
                // Resume id (Mobile-Harness `antigravityCommand`): --resume <convId>,
                // captured from the agent's `Initialized` event and persisted per
                // session so a mode change or respawn resumes instead of restarting.
                val args = ArrayList<String>()
                bundle.model?.let { if (it.isNotBlank()) args += listOf("-m", it) }
                bundle.mode?.let { if (it.isNotBlank()) args += listOf("--mode", it) }
                bundle.effort?.let { if (it.isNotBlank()) args += listOf("--effort", it) }
                resumeIdArgFor("antigravity")?.let { flag ->
                    bundle.resumeId?.let { if (it.isNotBlank()) args += listOf(flag, it) }
                }
                args
            }
            "opencode" -> {
                // opencode serve: --model, --variant. Verified against opencode.
                val args = ArrayList<String>()
                bundle.model?.let { if (it.isNotBlank()) args += listOf("--model", it) }
                bundle.mode?.let { if (it.isNotBlank()) args += listOf("--variant", it) }
                args
            }
            else -> emptyList() // dsh: preset handled separately; unsupported: n/a.
        }

        /**
         * Environment overrides for [bundle], or empty when none. DSH uses a
         * settings.yaml preset (not env) for its modes, so it returns nothing
         * here.
         */
        fun envFor(id: String, bundle: ControlBundle): Map<String, String> = when (id) {
            "codex" -> mapOf("CODEX_APPROVAL_MODE" to (bundle.mode?.let { m ->
                mapOf("plan" to "plan", "full-network" to "full-network", "auto" to "auto")
                    .getOrDefault(m, "strict")
            } ?: "strict"))
            else -> emptyMap()
        }

        /**
         * Write the DSH preset that [mode] maps to into `settings.yaml`'s
         * `agent-presets.default`.
         *
         * THIS is the actual mode-switch fix. DSH does NOT read `DSH_AGENT_MODE`
         * (grep the checkout finds zero references); it resolves presets from
         * settings.yaml at session creation, and a live session's preset is PINNED
         * (dsh-api-session-controller's assertPresetUnchanged throws if you try to
         * change it on a running session). So switching PTC -> standard requires
         * two things done here + by the caller:
         *   1. rewrite agent-presets.default (this function), AND
         *   2. terminate the live session and boot a new one (the service
         *      restarts the agent on config change, which is why setAgentMode
         *      calls restartService()).
         *
         * [dshHome] is `~/.dsh` relative to the agent's HOME (files/dsh-runtime).
         * Returns the preset written, or null when the agent has no DSH preset
         * (non-DSH agents / unsupported).
         *
         * Uses AgentModelSettings.replaceOrAppendSection so the agent's own
         * settings.yaml keys are not reformatted (a YAML round-trip would drop
         * the user's theme / model and break chat).
         */
        fun writeDshPreset(mode: AgentMode, dshHome: java.io.File): String? {
            val preset = dshPresetFor(mode)
            val settingsFile = java.io.File(dshHome, "settings.yaml")
            if (!settingsFile.isFile) return preset
            val section = "agent-presets:\n  default: $preset\n"
            settingsFile.writeText(
                AgentModelSettings.replaceOrAppendSection(settingsFile.readText(), "agent-presets", section)
            )
            return preset
        }
    }
}