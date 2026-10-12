package com.dsh.mobile.runtime

import android.content.Context
import com.dsh.mobile.data.AgentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs a chosen set of coding agents onto the device, on demand.
 *
 * ## Why this exists
 *
 * bootstrap.sh installs a fixed set of agents unconditionally on first boot,
 * whether the user wants them or not. This runs only the ones the user picked,
 * and only the ones not already on disk, so:
 *  - first boot installs nothing the user did not ask for;
 *  - reopening the app installs nothing at all.
 *
 * ## Failure isolation
 *
 * Each agent installs independently and a failure is recorded against that agent
 * only. One bad package must not take the rest down with it, and must not fail
 * the ones that already succeeded - the user asked for a set, and a partial set
 * that works beats a total failure.
 */
class AgentInstaller(private val context: Context) {

    data class Result(
        val spec: AgentSpec,
        val installed: Boolean,
        val output: String = "",
        val failure: String? = null,
    ) {
        val ok: Boolean get() = installed
    }

    /** Agents still needing installation, given what the user selected. */
    fun pending(selected: Set<String>, completed: Set<String>): List<AgentSpec> =
        AgentCatalogBridge.pending(selected, completed, agentsDir())

    /**
     * Install one agent. Runs npm through the same interpreter the agent itself
     * will use, so the installed tree matches what later boots expect.
     */
    suspend fun install(spec: AgentSpec): Result = withContext(Dispatchers.IO) {
        val dir = agentsDir()
        dir.mkdirs()
        val node = AgentRuntime.nodeBinary(context)
        if (!node.canExecute()) {
            return@withContext Result(
                spec,
                installed = false,
                failure = "No usable Node runtime; finish setup first.",
            )
        }
        // npm needs a writable HOME and cache inside the app sandbox; outside it
        // the install fails on permissions rather than on the network.
        val npmHome = File(context.filesDir, "npm-home").apply { mkdirs() }
        val npmCache = File(context.filesDir, "npm-cache").apply { mkdirs() }

        val log = StringBuilder()
        val proc = try {
            ProcessBuilder(
                listOf(
                    node.absolutePath,
                    npmCliPath(node).absolutePath,
                    "install", "-g", spec.npmPackage,
                ),
            ).apply {
                directory(dir)
                redirectErrorStream(true)
                environment()["NPM_CONFIG_PREFIX"] = dir.absolutePath
                // npm needs a writable cache inside the app sandbox.
                environment()["HOME"] = npmHome.absolutePath
                environment()["npm_config_cache"] = npmCache.absolutePath
            }.start()
        } catch (e: Exception) {
            return@withContext Result(spec, installed = false, failure = "Could not start npm: ${e.message}")
        }

        // Read the pipe on this thread so a chatty install cannot deadlock on a
        // full stdout buffer while we wait below.
        proc.inputStream.bufferedReader().forEachLine { line ->
            log.append(line).append('\n')
            if (log.length > 32_000) log.setLength(log.length - 16_000)
        }

        val finished = withContext(Dispatchers.IO) {
            runCatching { proc.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES) }.getOrDefault(false)
        }
        if (!finished) {
            proc.destroyForcibly()
            return@withContext Result(
                spec, installed = false, output = log.toString(),
                failure = "Install timed out after $INSTALL_TIMEOUT_MINUTES minutes.",
            )
        }

        val exit = proc.exitValue()
        // The exit code alone is not enough: npm can exit 0 with a warning while
        // the binary is still absent, which later shows up as a blank page. The
        // on-disk check is the real verdict.
        val landed = isOnDisk(spec, dir)
        if (exit == 0 && landed) {
            Result(spec, installed = true, output = log.toString())
        } else {
            Result(
                spec, installed = false, output = log.toString(),
                failure = if (exit != 0) "npm exited $exit" else "npm finished but the binary is missing",
            )
        }
    }

    /**
     * Install several agents, one at a time.
     *
     * Sequential by design: npm contends for the same prefix directory, and
     * parallel installs into one prefix corrupt each other's trees. Each result
     * is independent so one failure does not abort the rest.
     */
    suspend fun installAll(specs: List<AgentSpec>): List<Result> {
        val out = mutableListOf<Result>()
        for (spec in specs) {
            out += install(spec)
        }
        return out
    }

    /** Remove an installed agent's files to reclaim storage. */
    suspend fun uninstall(spec: AgentSpec): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = agentsDir()
            val target = if (spec.id == "dsh") File(dir, "dsh") else File(dir, spec.relativeBinary.substringBeforeLast('/'))
            if (target.exists()) target.deleteRecursively() else false
        }.getOrDefault(false)
    }

    fun isOnDisk(spec: AgentSpec, dir: File = agentsDir()): Boolean =
        if (spec.id == "dsh") {
            File(dir, "dsh/lib/bin.js").isFile
        } else {
            File(dir, spec.relativeBinary).isFile
        }

    fun agentsDir(): File = AgentRuntime.agentsDir(context)

    /** First non-blank line of a transcript, for the session list preview. */
    fun buildPreviewOf(logFile: File, maxChars: Int = 160): String {
        if (!logFile.isFile) return ""
        return runCatching {
            logFile.useLines { it.firstOrNull { l -> l.isNotBlank() }?.take(maxChars) ?: "" }
        }.getOrDefault("")
    }

    /**
     * npm's own JS entry point, next to the interpreter that will run it.
     *
     * Resolved from the runtime tree rather than assumed to be on PATH: npm is a
     * guest binary under PRoot and a bionic npm alongside libnode.so otherwise,
     * and neither is guaranteed to be in the child's PATH.
     */
    private fun npmCliPath(node: File): File {
        val candidates = listOf(
            File(node.parentFile, "node_modules/npm/bin/npm-cli.js"),
            File(AgentRuntime.runtimeRoot(context), "usr/bin/npm"),
            File(AgentRuntime.runtimeRoot(context), "usr/lib/node_modules/npm/bin/npm-cli.js"),
            File("/system/bin/npm"),
        )
        return candidates.firstOrNull { it.isFile } ?: candidates.first()
    }

    companion object {
        const val INSTALL_TIMEOUT_MINUTES = 10L
    }
}

/** Small indirection so the pending decision stays testable without a Context. */
internal object AgentCatalogBridge {
    fun pending(selected: Set<String>, completed: Set<String>, agentsDir: File): List<AgentSpec> =
        com.dsh.mobile.data.AgentInstallStore.pendingLogic(selected, completed, agentsDir) { spec ->
            val f = if (spec.id == "dsh") File(agentsDir, "dsh/lib/bin.js") else File(agentsDir, spec.relativeBinary)
            f.isFile
        }
}