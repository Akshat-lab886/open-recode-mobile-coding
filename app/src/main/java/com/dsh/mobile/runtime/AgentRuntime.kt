package com.dsh.mobile.runtime

import android.content.Context
import android.system.Os
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import java.io.File
import java.net.ServerSocket

/**
 * Owns the embedded rootless Linux/Node (PRoot) runtime layout and the
 * construction of agent child-process commands.
 *
 * The real process spawning happens in [AgentRuntimeService] via
 * [ProcessBuilder]; this object keeps every path, port, and env decision
 * in one testable place.
 *
 * Directory layout (app-private, no root required):
 * ```
 * filesDir/
 *   dsh-runtime/        <- PRoot distro root (mounted as / inside proot)
 *     node/             <- embedded Node 20 binary
 *     agents/           <- npm-installed agent CLIs (dsh, claude, codex, opencode)
 *   workspaces/         <- default workspace root if the user picked none
 *   dsh-logs/           <- stdout/stderr capture per agent
 * ```
 */
object AgentRuntime {

    const val LOOPBACK_HOST = "127.0.0.1"
    const val PORT_RANGE_START = 3080
    const val PORT_RANGE_END = 3999

    /**
     * In-PRoot path the workspace is bind-mounted at. It is a MOUNT POINT,
     * not a CLI flag — no backend here accepts `--workspace` (DSH does not),
     * so this constant must only ever be used for `-b`/`-w`.
     */
    const val WORKSPACE_MOUNT = "/workspace"

    fun runtimeRoot(context: Context): File =
        File(context.filesDir, "dsh-runtime").apply { mkdirs() }

    /**
     * Node binary **inside the rootfs** (host path).
     *
     * Node ships as Alpine's own `nodejs` apk within `rootfs.tar.gz` — there
     * is no separate `node/` directory. When the child runs under PRoot the
     * guest sees this as [GUEST_NODE_PATH].
     */
    fun nodeBinary(context: Context): File {
        val guest = File(runtimeRoot(context).absolutePath + "/usr/bin/node")
        if (guest.canExecute()) return guest
        // No PRoot guest. Fall back to the bionic Node bundled in the APK.
        // It lives in nativeLibraryDir because that is the only app location
        // where execve is still permitted at targetSdk 29+ (execute_no_trans
        // blocks filesDir, cacheDir and the external files dir).
        return nativeNode(context) ?: guest
    }

    /** The bundled bionic Node, or null when the APK has no payload. */
    fun nativeNode(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, NATIVE_NODE_NAME)
        return f.takeIf { it.canExecute() }
    }

    /**
     * Shared libraries for the bundled Node.
     *
     * These are assets, not jniLibs: aapt2 packages only `lib*.so` from
     * jniLibs, so versioned SONAMEs like libz.so.1, libssl.so.3 and
     * libicuuc.so.78 would be dropped without a word and the loader would
     * fail with "library libz.so.1 not found".
     */
    fun nativeLibsDir(context: Context): File =
        File(File(context.filesDir, "nativelibs-root"), "nativelibs")

    const val NATIVE_NODE_NAME = "libnode.so"

    /**
     * Absolute paths the bundled bionic runtime needs, as plain strings.
     *
     * Deliberately not a [Context]: the environment contract is asserted by
     * JVM unit tests that have no Android framework available, and passing a
     * Context here would force them to drop those tests.
     */
    data class NativePaths(
        val libsDir: String,
        val nativeLibDir: String,
        val opensslConf: String,
        val scratchDir: String,
        val homeDir: String,
    )

    fun nativePaths(context: Context): NativePaths {
        val libs = nativeLibsDir(context)
        val runtime = runtimeRoot(context)
        return NativePaths(
            libsDir = libs.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir,
            opensslConf = File(libs, "openssl.cnf").absolutePath,
            scratchDir = File(runtime, "tmp").apply { mkdirs() }.absolutePath,
            homeDir = runtime.absolutePath,
        )
    }

    /**
     * Agent CLIs (npm global prefix). Guest path [GUEST_AGENTS_DIR].
     *
     * NOTE: when the child runs under PRoot the guest root is the runtime root,
     * so argv MUST use guest paths — a host path like
     * `/data/data/…/dsh-runtime/agents` does not exist inside the guest.
     */
    fun agentsDir(context: Context): File =
        File(runtimeRoot(context), "agents").apply { mkdirs() }

    fun defaultWorkspace(context: Context): File =
        File(context.filesDir, "workspaces").apply { mkdirs() }

    fun logFile(context: Context, agent: ActiveAgent): File =
        File(context.filesDir, "dsh-logs/${agent.name.lowercase()}.log").apply {
            parentFile?.mkdirs()
        }

    /** Finds a free loopback port in the app's private range. */
    fun findFreePort(preferred: Int = 0): Int {
        if (preferred > 0 && isPortFree(preferred)) return preferred
        for (port in PORT_RANGE_START..PORT_RANGE_END) {
            if (isPortFree(port)) return port
        }
        throw IllegalStateException("No free loopback port available")
    }

    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).close()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Builds the argv for the requested agent. DSH serves its real web UI
     * (`dsh --profile web`); CLI-only agents are wrapped by the embedded
     * visual proxy gateway ([com.dsh.mobile.proxy.VisualGateway]).
     *
     * Agent modes are NOT passed as CLI flags — unknown flags would crash the
     * child. Modes are enforced at the app layer (gateway approval strictness
     * + DSH_AGENT_MODE env in [buildEnvironment]).
     */
    /**
     * The PRoot wrapper argv — pure, so the on-device-discovered bind rules can
     * be asserted on the JVM without a real `Context`.
     *
     * `/proc`, `/dev` and `/sys` are bound explicitly because a guest without
     * them cannot even open `/dev/null` (git fails immediately). `--link2symlink`
     * makes symlinks behave as the rootfs expects.
     */
    fun prootWrapperArgs(runtimeRootPath: String, workspacePath: String): List<String> = listOf(
        "-r", runtimeRootPath,
        // Without /dev the guest has no /dev/null and git dies with
        // "could not open '/dev/null': No such file or directory".
        "-b", "/proc",
        "-b", "/dev",
        "-b", "/sys",
        "-b", "$workspacePath:$WORKSPACE_MOUNT",
        "-w", WORKSPACE_MOUNT,
        // PRoot rewrites syscalls via ptrace; it does NOT emulate libc, so
        // the guest loader comes from the rootfs (musl). An Alpine node
        // therefore runs fine inside the rootfs and Android's bionic never
        // enters the guest process.
        "--link2symlink",
    )

    fun buildCommand(
        context: Context,
        agent: ActiveAgent,
        workspace: File,
        port: Int,
    ): List<String> {
        val prootFile = File(runtimeRoot(context).absolutePath + "/proot")
        // If the proot binary hasn't been extracted yet, run natively.
        if (!prootFile.canExecute()) return buildArgv(agent, context, port)

        // CRITICAL: inside PRoot the guest root IS the runtime root, so argv
        // must use guest paths. Passing host paths such as
        // /data/data/com.dsh.mobile/files/dsh-runtime/... would fail with
        // "No such file or directory" because those paths do not exist in the
        // guest's view. The same files are staged on the host under the runtime
        // root and therefore appear at these guest paths.
        return listOf(prootFile.absolutePath) +
            prootWrapperArgs(runtimeRoot(context).absolutePath, workspace.absolutePath) +
            buildArgv(
                agent = agent,
                nodePath = GUEST_NODE_PATH,
                agentsDir = GUEST_AGENTS_DIR,
                port = port,
            )
    }

    /** Guest path of the Node binary once the rootfs is unpacked. */
    const val GUEST_NODE_PATH = "/usr/bin/node"

    /** Guest path of the npm global prefix (agents live at `/agents`). */
    const val GUEST_AGENTS_DIR = "/agents"

    /** Guest path of the unpacked rootfs (this is also the proot `-r` value). */
    const val GUEST_ROOT = "/"

    /**
     * Guest `PATH` for the PRoot child.
     *
     * Verified on-device: the host Android PATH (`/product/bin:/system/bin:…`)
     * otherwise leaks into the guest, where `cat`, `ls` and `uname` are all
     * "not found". Any agent spawning `git`/`rg`/`bash` by NAME fails. These
     * directories must come first; the Android tail is kept last so an agent
     * can still reach a host tool if it genuinely needs one.
     */
    const val GUEST_PATH =
        "/usr/local/bin:/usr/bin:/bin:/sbin:/root/bin:/product/bin:/system/bin"

    /**
     * Guest DNS. A stock Alpine minirootfs has no `/etc/resolv.conf`, so
     * without this the guest cannot resolve `registry.npmjs.org` and agent
     * installs fail with a confusing network error.
     */
    const val RESOLV_CONF_CONTENT = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"

    private fun buildArgv(agent: ActiveAgent, context: Context, port: Int): List<String> =
        buildArgv(
            agent = agent,
            nodePath = nodeBinary(context).absolutePath,
            agentsDir = agentsDir(context).absolutePath,
            port = port,
        )

    /**
     * Absolute path of the installed DSH entry point.
     *
     * VERIFIED from the package manifest and an on-device `npm install -g`:
     * `@deepseek-ai/dsh` declares `"bin": {"dsh": "lib/bin.js"}`, and npm lays
     * the tree out as
     *   <agents>/bin/dsh                                            (shim)
     *   <agents>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js        (real)
     * We invoke the real file through the guest's node rather than the shim so
     * the shebang never has to resolve `env node` on a musl guest.
     */
    fun dshEntryPoint(agentsDir: String): String = dshEntryPoint(File(agentsDir))

    /**
     * Two layouts are real and both occur:
     *   bundled payload  -> <agents>/dsh/lib/bin.js
     *   npm --prefix     -> <agents>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
     *
     * VERIFIED on device: the bundled tarball unpacks to the first, while an
     * on-device `npm install --prefix` produces the second. Pointing at only
     * the npm layout leaves the bundled install with a path that does not
     * exist, which the child reports as a bare module-not-found and the UI
     * shows as a loading screen that never resolves.
     */
    fun dshEntryPoint(agentsDir: File): String {
        val bundled = File(agentsDir, "dsh/lib/bin.js")
        if (bundled.isFile) return bundled.absolutePath
        val npmLayout = File(agentsDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (npmLayout.isFile) return npmLayout.absolutePath
        return bundled.absolutePath // report the expected primary path on failure
    }

    /**
     * The agent argv WITHOUT the PRoot wrapper — split out from [buildCommand]
     * so the flag contract can be asserted on the JVM, where `Context` (and
     * therefore `filesDir`) does not exist.
     *
     * Every flag below is checked against the real CLI's `--help` output: an
     * unknown flag makes the child exit non-zero, which the user experiences
     * as "the agent never opened its port" instead of a readable crash.
     */
    fun buildArgv(
        agent: ActiveAgent,
        nodePath: String,
        agentsDir: String,
        port: Int,
    ): List<String> = when (agent) {
        // DSH exposes the web server only through a PROFILE, not a
        // subcommand: `dsh web` is an unknown command. The profile is
        // also what makes `--host/--port/--no-open/--trusted-host`
        // available at all.
        //
        // `--no-open` is mandatory on a phone: without it the server
        // tries to launch a desktop browser on the device, which has
        // none. The port is explicit because [findFreePort] already
        // picked one; `--port 0` would work too but then the app would
        // have no port to bind the WebView to.
        //
        // There is deliberately NO `--workspace` flag: `dsh --profile web`
        // does not accept one, and an unknown option makes it exit
        // non-zero ("error: unknown option '--workspace'"). The workspace
        // is already the child's CWD and the PRoot `/workspace` bind.
        ActiveAgent.DEEPSEEK_HARNESS -> listOf(
            // ORDER MATTERS. ProcessBuilder treats argv[0] as the executable to
            // run, so nodePath MUST be first - putting "--expose-internals"
            // there makes Java try to exec a file with that name and the child
            // dies with IOException error=2 (ENOENT) before node ever starts.
            nodePath,
            // REQUIRED, verified on device: without it the plugin tree refuses
            // to load with
            //   cordis-plugin-hmr: --expose-internals is required for HMR service
            // and the server never starts. This is a NODE flag, so it goes
            // after the interpreter and before the script.
            "--expose-internals",
            // VERIFIED entry point: the declared bin is lib/bin.js. See
            // [dshEntryPoint] for why two parent layouts are accepted.
            dshEntryPoint(agentsDir),
            "--profile", "web",
            "--host", LOOPBACK_HOST,
            "--port", port.toString(),
            "--no-open",
        )
        // CLI-only agents: boot behind the visual gateway so the user
        // still gets a chat-and-file interface instead of a terminal.
        // The gateway wrapper serves its chat UI on the loopback port
        // (health-checked like every other backend) while keeping the
        // child's stdin/stdout pipes attached for approval interception.
        ActiveAgent.CLAUDE_CODE -> listOf(
            nodePath, "$agentsDir/visual-gateway/gateway.js",
            "--backend", "claude",
            "--port", port.toString(),
            "--host", LOOPBACK_HOST,
            "--", "$agentsDir/claude/bin/claude",
        )
        ActiveAgent.CODEX -> listOf(
            nodePath, "$agentsDir/visual-gateway/gateway.js",
            "--backend", "codex",
            "--port", port.toString(),
            "--host", LOOPBACK_HOST,
            "--", "$agentsDir/codex/bin/codex",
        )
        // NOT independently verified against the real `opencode serve
        // --help` — unlike the DSH branch, this one is carried over
        // unchanged, so treat its flag set as unconfirmed.
        ActiveAgent.OPENCODE -> listOf(
            nodePath, "$agentsDir/opencode/bin/opencode",
            "serve",
            "--host", LOOPBACK_HOST,
            "--port", port.toString(),
            "--workspace", WORKSPACE_MOUNT,
        )
    }

    /**
     * On-disk entry point of each agent, or null when it is not installed.
     *
     * VERIFIED ON DEVICE: a clean install ships only `dsh` and the gateway.
     * The app happily selected an agent whose binary was absent, spawned the
     * gateway pointed at a path that did not exist, and the user saw a blank
     * page with no error - "the app is installed and nothing works". Every
     * agent decision must therefore be checked against what is actually on
     * disk before anything is spawned.
     */
    fun agentEntryPoint(agent: ActiveAgent, agentsDir: File): File? = when (agent) {
        ActiveAgent.DEEPSEEK_HARNESS -> File(dshEntryPoint(agentsDir)).takeIf { it.isFile }
        ActiveAgent.CLAUDE_CODE -> File(agentsDir, "claude/bin/claude").takeIf { it.isFile }
        ActiveAgent.CODEX -> File(agentsDir, "codex/bin/codex").takeIf { it.isFile }
        ActiveAgent.OPENCODE -> File(agentsDir, "opencode/bin/opencode").takeIf { it.isFile }
    }

    fun isAgentInstalled(agent: ActiveAgent, agentsDir: File): Boolean =
        agentEntryPoint(agent, agentsDir) != null

    fun installedAgents(agentsDir: File): List<ActiveAgent> =
        ActiveAgent.entries.filter { isAgentInstalled(it, agentsDir) }

    /**
     * The agent to actually run.
     *
     * Prefers [preferred] when its binary exists, otherwise the first agent
     * that is genuinely installed. Returning the preferred agent regardless
     * would reproduce the blank-page bug, so this never returns an agent whose
     * entry point is missing while another one is present.
     */
    fun resolveRunnableAgent(preferred: ActiveAgent, agentsDir: File): ActiveAgent =
        if (isAgentInstalled(preferred, agentsDir)) preferred
        else installedAgents(agentsDir).firstOrNull() ?: preferred

    /** CLI-only agents render through the in-app visual gateway (no TCP server). */
    fun isGatewayAgent(agent: ActiveAgent): Boolean =
        agent == ActiveAgent.CLAUDE_CODE || agent == ActiveAgent.CODEX

    /** Full environment for the child: silent key injection + workspace config. */
    fun buildEnvironment(
        apiEnv: Map<String, String>,
        workspace: File,
        mode: AgentMode,
        port: Int,
        insideProot: Boolean,
        prootTmpDir: String = "",
        native: NativePaths? = null,
    ): Map<String, String> = buildMap {
        putAll(apiEnv)
        put("DSH_WORKSPACE", workspace.absolutePath)
        put("DSH_AGENT_MODE", mode.name)
        put("PORT", port.toString())
        put("HOST", LOOPBACK_HOST)
        put("TERM", "xterm-256color")
        // Keep child traffic on-device: the UI is served via loopback only.
        put("NO_UPDATE_CHECK", "1")
        if (!insideProot) {
            // The bundled bionic Node. All three of these are required and all
            // three fail silently-or-confusingly without them:
            //
            // OPENSSL_CONF  Termux's Node hardcodes its OpenSSL config to
            //   Termux's own prefix (/data/data/com.termux/...), which does not
            //   exist here; every run then aborts with
            //   BIO_new_file:Permission denied. We ship our own openssl.cnf.
            // LD_LIBRARY_PATH  the versioned shared libraries are assets,
            //   unpacked to filesDir, not next to the interpreter in
            //   nativeLibraryDir.
            // TMPDIR/HOME  os.tmpdir() likewise returns the Termux prefix.
            // native is null only in unit tests that do not exercise the
            // bundled-runtime branch; a real call always supplies it.
            if (native != null) {
                put("LD_LIBRARY_PATH", "${native.libsDir}:${native.nativeLibDir}")
                put("OPENSSL_CONF", native.opensslConf)
                put("TMPDIR", native.scratchDir)
                put("HOME", native.homeDir)
            }
            put("npm_config_registry", "https://registry.npmjs.org")
        }
        if (insideProot) {
            // Inside PRoot the inherited Android PATH makes guest binaries
            // unfindable, and HOME must exist inside the rootfs for npm/git.
            put("PATH", GUEST_PATH)
            put("HOME", "/root")
            put("TMPDIR", "/tmp")
            // Alpine is musl; this keeps native addons on the right loader.
            put("npm_config_registry", "https://registry.npmjs.org")
            // REQUIRED, not optional. Without it proot aborts with
            //   "can't create temporary file: Permission denied"
            // because it defaults to /tmp, which an Android app cannot write.
            // Verified on-device: with this set, node runs from the app's own
            // data dir; without it every spawn fails immediately.
            put("PROOT_TMP_DIR", prootTmpDir)
        }
    }

    /**
     * App-private, always-writable scratch directory for proot.
     * Call before spawning so the directory exists.
     */
    fun prootTmpDir(context: Context): String {
        val dir = File(runtimeRoot(context).absolutePath + "/tmp")
        dir.mkdirs()
        return dir.absolutePath
    }

    /** True when [buildCommand] will wrap the child in PRoot. */
    fun isProotActive(context: Context): Boolean =
        File(runtimeRoot(context).absolutePath + "/proot").canExecute()

    /**
     * Writes the guest `/etc/resolv.conf`.
     *
     * A stock Alpine minirootfs has no resolv.conf, so without this the guest
     * cannot resolve registry.npmjs.org and installs fail. Call before boot.
     * Returns false if the rootfs is not writable yet.
     */
    fun ensureGuestDns(context: Context): Boolean = try {
        val etc = File(runtimeRoot(context), "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(RESOLV_CONF_CONTENT)
        true
    } catch (_: Exception) {
        false
    }

    // -- runtime payload ---------------------------------------------------

    fun runtimeAssets(context: Context): RuntimeAssets = RuntimeAssets(runtimeRoot(context))

    /**
     * Reads `assets/runtime-assets.json`. Returns null (rather than throwing)
     * so a malformed manifest degrades to "unknown payload" instead of
     * crashing the boot sequence.
     */
    fun loadManifest(context: Context): RuntimeAssetManifest? = try {
        context.assets.open(MANIFEST_ASSET).use {
            RuntimeAssetManifest.parse(it.readBytes().decodeToString())
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Copies the gateway bundle out of the APK into the runtime agents dir.
     * [buildCommand] points argv at `agents/visual-gateway/gateway.js`, which
     * does not exist on the filesystem until this runs.
     */
    fun stageBundledAssets(context: Context) {
        runtimeAssets(context).stageBundledAssets(
            openSource = { rel ->
                try {
                    context.assets.open(rel)
                } catch (_: Exception) {
                    null
                }
            },
            assetSink = { rel -> File(agentsDir(context), rel) },
        )
    }

    /** What the payload looks like right now, for status/diagnostics UI. */
    fun payloadState(context: Context): RuntimePayloadState {
        val manifest = loadManifest(context) ?: return RuntimePayloadState.Unknown
        return runtimeAssets(context).evaluate(manifest)
    }

    const val MANIFEST_ASSET = "runtime-assets.json"
}

/** Best-effort helper to mirror a SAF tree into the loopback workspace mount. */
fun mirrorSafTreeToWorkspace(context: Context, sourceTree: File, workspace: File) {
    // SAF URIs are resolved by the ViewModel into an app-private staging dir
    // before this is called; here we simply ensure the mount point exists and
    // record the mapping for the gateway's file browser.
    workspace.mkdirs()
    try {
        Os.symlink(sourceTree.absolutePath, File(workspace, ".saf-source").absolutePath)
    } catch (_: Exception) {
        // Symlink may already exist — harmless.
    }
}
