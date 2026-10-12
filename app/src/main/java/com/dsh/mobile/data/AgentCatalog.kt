package com.dsh.mobile.data

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
    /** npm package installed by the first-run installer. */
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
            npmPackage = "hermes-agent",
            approxInstallBytes = 90L * 1024 * 1024,
            defaultPort = 3094,
            relativeBinary = "hermes-agent/bin/hermes-agent",
            needsGateway = true,
            gatewayBackend = "hermes",
            argvVerified = false,
            // VERIFIED on npm: hermes-agent@0.21.6 is described by its own
            // author as "Unofficial npm bridge for Hermes Agent". Shown as
            // unofficial rather than passed off as the vendor's package.
            provenanceNote = "Unofficial third-party npm bridge, not published by Hermes.",
        ),
        AgentSpec(
            id = "antigravity",
            label = "Antigravity CLI",
            npmPackage = "antigravity",
            approxInstallBytes = 1L * 1024 * 1024,
            defaultPort = 3095,
            relativeBinary = "antigravity/bin/antigravity.js",
            needsGateway = true,
            gatewayBackend = "antigravity",
            argvVerified = false,
            // VERIFIED on npm: `antigravity` is version 0.0.0 and describes
            // itself as "placeholder for the haters". It is NOT Google's
            // product and there is no real Google package under this name.
            // Offered, but never labelled as Antigravity without this caveat.
            provenanceNote = "Placeholder package (v0.0.0) on npm - not a Google release.",
        ),
    )

    private val byId: Map<String, AgentSpec> = all.associateBy { it.id }
    private val byLabel: Map<String, AgentSpec> = all.associateBy { it.label }

    fun specFor(id: String): AgentSpec? = byId[id]

    /** True when [id] names a known agent. Used to validate persisted config. */
    fun isKnown(id: String): Boolean = byId.containsKey(id)

    /** Every agent the picker may offer. */
    val installable: List<AgentSpec> = all.filter { it.available }

    /** Total on-device cost of installing every agent, for the picker summary. */
    val totalInstallBytes: Long get() = all.sumOf { it.approxInstallBytes }
}