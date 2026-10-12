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
            provenanceNote = "Closed source (Google). Uses Google's official native " +
                "Android binary; the npm name 'antigravity' is an unrelated " +
                "placeholder and is deliberately not installed.",
        ),
    )

    private val byId: Map<String, AgentSpec> = all.associateBy { it.id }

    fun specFor(id: String): AgentSpec? = byId[id]

    /** Every agent the picker may offer. */
    val installable: List<AgentSpec> = all.filter { it.available }

    /** Total on-device cost of installing every agent, for the picker summary. */
    val totalInstallBytes: Long get() = all.sumOf { it.approxInstallBytes }
}