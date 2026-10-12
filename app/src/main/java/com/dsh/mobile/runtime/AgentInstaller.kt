package com.dsh.mobile.runtime

import android.os.Build
import java.net.URL
import java.util.zip.GZIPInputStream
import android.content.Context
import com.dsh.mobile.data.AgentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
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
        when (val kind = spec.install) {
            is com.dsh.mobile.data.InstallKind.Tarball -> installTarball(spec, kind)
            is com.dsh.mobile.data.InstallKind.NPM -> installViaNpm(spec, kind.packageName)
            // Reached only if the caller ignores `available`. Failing loudly is
            // better than starting a download that cannot succeed on a phone.
            is com.dsh.mobile.data.InstallKind.Unsupported -> Result(
                spec = spec,
                installed = false,
                output = "",
                failure = kind.reason,
            )
        }
    }

    /**
     * Download a release tarball and unpack the executable.
     *
     * Used for Antigravity CLI, which is a Go static binary rather than an npm
     * package. The npm name for it is an unrelated placeholder, so this path is
     * the only way to get the real tool.
     */
    private suspend fun installTarball(
        spec: AgentSpec,
        kind: com.dsh.mobile.data.InstallKind.Tarball,
    ): Result = withContext(Dispatchers.IO) {
        val dir = agentsDir()
        dir.mkdirs()
        val url = kind.urlTemplate.replace("{{arch}}", deviceArch())
        val log = StringBuilder("GET $url\n")
        return@withContext try {
            val tmp = File(context.cacheDir, "${spec.id}.tar.gz")
            URL(url).openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
            log.append("downloaded ${tmp.length()} bytes\n")

            // Unpack only the executable we need: the archive also ships docs
            // and checksums, and there is no room in the APK sandbox for waste.
            //
            // TAR is parsed by hand rather than via commons-compress, which
            // would be a new dependency. The format is a 512-byte header block
            // per entry (name in 0..100, size as octal at 124..136, type flag at
            // 156) followed by the payload padded to 512.
            val target = File(dir, spec.relativeBinary)
            target.parentFile?.mkdirs()
            var found = false
            GZIPInputStream(tmp.inputStream().buffered()).use { gz ->
                val header = ByteArray(512)
                while (true) {
                    if (!readFully(gz, header)) break
                    if (header.all { it == 0.toByte() }) continue  // end-of-archive padding
                    val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    val sizeField = String(header, 124, 12, Charsets.UTF_8)
                        .trimEnd('\u0000', ' ')
                    val size = sizeField.toLongOrNull(8) ?: 0L
                    val typeFlag = header[156].toInt().toChar()
                    val isFile = typeFlag == '0' || typeFlag == '\u0000'

                    if (isFile && File(name).name == kind.binaryInArchive) {
                        val payload = ByteArray(size.toInt())
                        readFully(gz, payload)
                        target.outputStream().use { it.write(payload) }
                        found = true
                    } else {
                        skipFully(gz, size)
                    }
                    // Payload is padded to the next 512-byte boundary.
                    val pad = (512 - (size % 512)) % 512
                    if (pad > 0) skipFully(gz, pad)
                }
            }
            tmp.delete()
            if (!found || !target.isFile || target.length() == 0L) {
                Result(spec, installed = false, output = log.toString(),
                    failure = "Archive did not contain '${kind.binaryInArchive}'")
            } else {
                target.setExecutable(true, false)
                log.append("unpacked to ${target.absolutePath}\n")
                Result(spec, installed = true, output = log.toString())
            }
        } catch (e: Exception) {
            Result(spec, installed = false, output = log.toString(),
                failure = "Download failed: ${e.message}")
        }
    }

    /** Fill [buf] completely, or return false if the stream ends first. */
    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) return false
            if (n == 0) continue
            total += n
        }
        return true
    }

    /** Skip [count] bytes, tolerating a short read. */
    /** Copies exactly [count] bytes without buffering the whole payload. */
    private fun copyExactly(inp: java.io.InputStream, out: java.io.OutputStream, count: Long) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                // skip() may legitimately return 0; fall back to reading.
                if (input.read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    /** `arm64` for the release-asset naming Antigravity uses. */
    private fun deviceArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a", "armeabi-v8a" -> "arm64"
        "x86_64" -> "x64"
        else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
    }

    /**
     * Agents whose real CLI is a native binary that npm refuses to fetch here.
     *
     * npm skips a platform-gated optionalDependency whenever `process.platform`
     * is not one it recognises. On Android it is "android", so the linux-arm64
     * binary is skipped and only the ~31 KB JavaScript shim lands - the agent
     * then looks installed while failing to run.
     *
     * The tarball is therefore fetched by explicit URL, which bypasses the
     * platform gate, and unpacked to the exact path the shim looks for.
     *
     * Verified from the published artifacts:
     *  - `@openai/codex` installs the musl build, a STATIC aarch64 ELF (no
     *    PT_INTERP, no PT_DYNAMIC), which is why it can run on bionic at all.
     *    Its `bin/codex.js` explicitly maps `process.platform === "android"`
     *    to that same musl triple: the vendor intends it to run here.
     *  - `@anthropic-ai/claude-code` has no equivalent. Its Android native
     *    packages are not published (they 404 from the registry), so its
     *    `bin/claude.exe` stays a 500-byte stub that prints
     *    "Error: claude native binary not installed." It is deliberately absent
     *    here rather than installed-and-broken.
     */
    private data class NativePayload(
        val id: String,
        val url: String,
        /** Path inside the tarball that holds the executable. */
        val binaryInArchive: String,
        /** Path under the agent's package dir the launcher expects. */
        val binaryRelativeTo: String,
    )

    private val nativePayloads = listOf(
        NativePayload(
            id = "codex",
            url = "https://registry.npmjs.org/@openai/codex/-/codex-0.162.1-linux-arm64.tgz",
            binaryInArchive = "vendor/aarch64-unknown-linux-musl/bin/codex",
            binaryRelativeTo = "vendor/aarch64-unknown-linux-musl/bin/codex",
        ),
    )

    /** True when this agent's real CLI is already unpacked on disk. */
    private fun nativeBinaryOnDisk(spec: AgentSpec, dir: File): Boolean {
        val payload = nativePayloads.firstOrNull { it.id == spec.id } ?: return true
        val f = File(dir, "lib/node_modules/${spec.npmPackage}/${payload.binaryRelativeTo}")
        return f.isFile && f.length() > 0
    }

    /** True when [spec] has a native binary step after the JavaScript install. */
    internal fun needsNativePayload(spec: AgentSpec): Boolean =
        nativePayloads.any { it.id == spec.id }

    private suspend fun installViaNpm(spec: AgentSpec, pkg: String): Result =
        withContext(Dispatchers.IO) {
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
        val log = StringBuilder()

        // npm is pure JavaScript and ships inside the APK as npm-cli.tar.gz,
        // unpacked to <runtime>/npm by RuntimeAssets.stageNpm. It therefore runs
        // on the same bionic libnode.so that already runs DeepSeek Harness.
        //
        // Neither of the previous attempts worked here:
        //   1. bundling npm beside libnode.so:
        //      Error: Cannot find module '.../lib/arm64/node_modules/npm/bin/npm-cli.js'
        //   2. reaching for the guest npm under PRoot: proot is not installed,
        //      because the 46 MB Alpine rootfs was never published, so there
        //      was no guest npm to run.
        val npmCli = File(AgentRuntime.runtimeRoot(context), "npm/bin/npm-cli.js")
        if (!npmCli.isFile) {
            return@withContext Result(
                spec,
                installed = false,
                failure = "The npm CLI is missing from the runtime. Reinstall the app.",
            )
        }
        val npmHome = File(context.filesDir, "npm-home").apply { mkdirs() }
        val npmCache = File(context.filesDir, "npm-cache").apply { mkdirs() }

        val command = listOf(
            node.absolutePath,
            npmCli.absolutePath,
            "install", "-g", pkg,
        )

        val proc = try {
            ProcessBuilder(command).apply {
                directory(dir)
                redirectErrorStream(true)
                environment()["NPM_CONFIG_PREFIX"] = dir.absolutePath
                // npm needs a writable cache inside the app sandbox.
                environment()["HOME"] = npmHome.absolutePath
                environment()["npm_config_cache"] = npmCache.absolutePath
                // REQUIRED, not optional. The bundled libnode.so links against
                // libz.so.1 and friends, which are shipped as assets unpacked to
                // filesDir rather than sitting beside the interpreter in
                // nativeLibraryDir. Without this the process is killed by the
                // linker before npm ever starts:
                //   CANNOT LINK EXECUTABLE "libnode.so": library "libz.so.1" not found
                val native = AgentRuntime.nativePaths(context)
                environment()["LD_LIBRARY_PATH"] = "${native.libsDir}:${native.nativeLibDir}"
                environment()["OPENSSL_CONF"] = native.opensslConf
                environment()["TMPDIR"] = native.scratchDir
                // Alpine is musl; without this PRoot aborts with
                // "can't create temporary file: Permission denied".
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["npm_config_registry"] = "https://registry.npmjs.org"
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

        // Native binary LAST, after npm has finished.
        //
        // Order matters and the wrong order fails silently: `npm install -g`
        // replaces node_modules wholesale, so unpacking the binary beforehand
        // leaves a correct 255 MB file that npm then deletes. The install still
        // reported success, and `isOnDisk` still passed, because the JavaScript
        // launcher was there - so the agent looked installed and could not run.
        val payload = nativePayloads.firstOrNull { it.id == spec.id }
        if (landed && payload != null && !nativeBinaryOnDisk(spec, dir)) {
            if (!installNativePayload(spec, payload)) {
                return@withContext Result(
                    spec,
                    installed = false,
                    failure = "Installed the launcher but not its native CLI. " +
                        "The agent would fail to run, so it is not being marked installed.",
                )
            }
        }
        if (exit == 0 && landed) {
            Result(spec, installed = true, output = log.toString())
        } else {
            // Say WHY, using npm's own last lines. "npm exited 1" on its own is
            // what made this failure invisible: the real reason (a missing
            // shared library, a 403, an out-of-space error) was captured here
            // and then thrown away, so the app could only report that
            // something had failed without ever saying what.
            Result(
                spec, installed = false, output = log.toString(),
                failure = describeFailure(exit, landed, log.toString()),
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


    /**
     * Fetch a platform-gated native binary by explicit URL and place it where
     * the agent's JavaScript launcher looks for it.
     *
     * Installing by tarball URL sidesteps npm's os/cpu gate entirely, which is
     * the only way to get a linux-arm64 payload onto Android. The download is
     * streamed straight into a temp file because the artifact is ~150 MB and
     * the app has no room to buffer it in memory.
     */
    private suspend fun installNativePayload(spec: AgentSpec, payload: NativePayload): Boolean =
        withContext(Dispatchers.IO) {
            val pkgDir = File(agentsDir(), "lib/node_modules/${spec.npmPackage}")
            val target = File(pkgDir, payload.binaryRelativeTo)
            if (target.isFile && target.length() > 0) return@withContext true
            val tmp = File(context.cacheDir, "native-${payload.id}.tgz")
            return@withContext try {
                java.net.URL(payload.url).openStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                target.parentFile?.mkdirs()
                var found = false
                java.util.zip.GZIPInputStream(tmp.inputStream().buffered()).use { gz ->
                    val header = ByteArray(512)
                    while (true) {
                        if (!readFully(gz, header)) break
                        if (header.all { it == 0.toByte() }) continue
                        val name = String(header, 0, 100, Charsets.UTF_8)
                            .trimEnd('\u0000', ' ')
                        val size = String(header, 124, 12, Charsets.UTF_8)
                            .trimEnd('\u0000', ' ')
                            .toLongOrNull(8) ?: 0L
                        val typeFlag = header[156].toInt().toChar()
                        if (name.endsWith(payload.binaryInArchive) &&
                            (typeFlag == '0' || typeFlag == '\u0000')
                        ) {
                            // STREAMED, never buffered. The binary is ~255 MB;
                            // `ByteArray(size)` throws OutOfMemoryError on a
                            // handset, and `catch (e: Exception)` does not
                            // catch an Error - so the install reported success
                            // with nothing on disk.
                            copyExactly(gz, target.outputStream(), size)
                            found = target.length() == size
                            Log.i(
                                "AgentInstaller",
                                "native payload: wrote $size bytes for ${payload.id}",
                            )
                        } else {
                            skipFully(gz, size)
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) skipFully(gz, pad)
                    }
                }
                found && target.setExecutable(true)
            } catch (e: Exception) {
                android.util.Log.w("AgentInstaller", "native payload failed: ${e.message}")
                false
            } finally {
                tmp.delete()
            }
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

        /**
         * A failure message a user can act on, built from npm's own last lines.
         *
         * npm's output is the only evidence available when an install fails,
         * and discarding it is what turned a missing shared library into an
         * unexplained failure for every optional agent. Lives in the companion
         * so it needs no Context and can be asserted on the JVM.
         */
        internal fun describeFailure(exit: Int, landed: Boolean, log: String): String {
            val tail = log.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeLast(3)
                .joinToString(" | ")
                .take(240)
            val headline = when {
                !landed && exit == 0 -> "npm finished but the agent binary is missing"
                exit != 0 -> "npm exited $exit"
                else -> "install did not complete"
            }
            return if (tail.isBlank()) headline else "$headline: $tail"
        }
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
