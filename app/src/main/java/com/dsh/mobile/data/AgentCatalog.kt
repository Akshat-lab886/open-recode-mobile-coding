package com.dsh.mobile.data

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
}

/**
 * The catalogue of coding agents this app knows how to install and run.
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
     * Provenance note shown in the picker.
     *
     * Empty means the upstream package is the vendor's own. A non-empty note is
     * shown to the user rather than silently presenting an unofficial bridge as
     * the official product.
     */
    val provenanceNote: String = "",
) {
    /** True when the agent may be offered to the user at all. */
    val available: Boolean get() = npmPackage.isNotBlank()
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
        ),
        AgentSpec(
            id = "claude",
            label = "Claude Code",
            install = InstallKind.NPM("@anthropic-ai/claude-code"),
            npmPackage = "@anthropic-ai/claude-code",
            approxInstallBytes = 260L * 1024 * 1024,
            defaultPort = 3091,
            relativeBinary = "claude/bin/claude",
            needsGateway = true,
            gatewayBackend = "claude",
            argvVerified = true,
        ),
        AgentSpec(
            id = "codex",
            label = "Codex",
            install = InstallKind.NPM("@openai/codex"),
            npmPackage = "@openai/codex",
            approxInstallBytes = 220L * 1024 * 1024,
            defaultPort = 3092,
            relativeBinary = "codex/bin/codex",
            needsGateway = true,
            gatewayBackend = "codex",
            argvVerified = true,
        ),
        AgentSpec(
            id = "opencode",
            label = "OpenCode",
            install = InstallKind.NPM("opencode-ai"),
            npmPackage = "opencode-ai",
            approxInstallBytes = 200L * 1024 * 1024,
            defaultPort = 3093,
            relativeBinary = "opencode/bin/opencode",
            needsGateway = false,
            gatewayBackend = null,
            // Carried over unchanged from the original service; the flag set
            // has never been checked against the real `opencode serve --help`.
            argvVerified = false,
        ),
        AgentSpec(
            id = "hermes",
            label = "Hermes Agent",
            // NOT an npm package. The npm name is a third-party bridge that then
            // downloads the real runtime, so npm is the wrong mechanism here.
            install = InstallKind.NPM("hermes-agent"),
            npmPackage = "hermes-agent",
            // VERIFIED: Hermes provisions its own Python 3.14 plus pinned
            // Node, ripgrep, FFmpeg and (by default) a Chromium download for
            // computer use. That is a 1-3 GB footprint, not a 90 MB CLI - and
            // it does not fit alongside the agents on a phone with a few GB
            // free. The picker shows this number so the cost is visible before
            // the user commits to it.
            approxInstallBytes = 1536L * 1024 * 1024,
            defaultPort = 3094,
            relativeBinary = "hermes-agent/bin/hermes-agent",
            needsGateway = true,
            gatewayBackend = "hermes",
            argvVerified = false,
            // VERIFIED on npm: hermes-agent@0.21.6 is described by its own
            // author as "Unofficial npm bridge for Hermes Agent"; upstream
            // marks pip/uv/brew as unsupported. It is also a general agent
            // that delegates to Claude Code/Codex rather than a direct peer.
            provenanceNote = "Unofficial npm bridge, not a Nous Research package. " +
                "Large (1-3 GB) and delegates coding to other agents.",
        ),
        AgentSpec(
            id = "antigravity",
            label = "Antigravity CLI",
            // VERIFIED: Google's Antigravity CLI is a Go static binary
            // distributed via GitHub releases, NOT via npm. Release 1.3.3
            // publishes agy_cli_linux_arm64.tar.gz (56.3 MB) and a musl
            // variant (54.4 MB). Downloading that directly is both correct and
            // far smaller than anything npm could offer.
            install = InstallKind.Tarball(
                urlTemplate = "https://github.com/google-antigravity/antigravity-cli/" +
                    "releases/latest/download/agy_cli_linux_{{arch}}.tar.gz",
                // VERIFIED by downloading release 1.3.3 and listing the archive:
                // the executable inside is named `antigravity`, NOT `agy`. The
                // command you type is `agy`; the file on disk is `antigravity`.
                // Matching on "agy" would have extracted nothing at all.
                binaryInArchive = "antigravity",
            ),
            // Recorded for display only: this is NOT an npm package. Showing
            // the npm name here would be a lie, because `antigravity` on npm is
            // an unrelated placeholder whose bin is `kirox`.
            npmPackage = "agy_cli_linux_arm64.tar.gz (GitHub release)",
            // 56 MB compressed, but 204 MB on disk once unpacked - and the
            // picker's cost column has to show what the user's storage actually
            // gives up, not the download size.
            approxInstallBytes = 204L * 1024 * 1024,
            defaultPort = 3095,
            relativeBinary = "agy/bin/agy",
            needsGateway = true,
            gatewayBackend = "antigravity",
            argvVerified = false,
            // Antigravity CLI is closed source and replaced Gemini CLI as
            // Google's terminal agent. Worth stating plainly next to it.
            provenanceNote = "Closed source (Google). Replaces Gemini CLI as Google's " +
                "terminal agent. Closed-source binary.",
        ),
    )

    private val byId: Map<String, AgentSpec> = all.associateBy { it.id }

    fun specFor(id: String): AgentSpec? = byId[id]

    /** Every agent the picker may offer. */
    val installable: List<AgentSpec> = all.filter { it.available }

    /** Total on-device cost of installing every agent, for the picker summary. */
    val totalInstallBytes: Long get() = all.sumOf { it.approxInstallBytes }
}