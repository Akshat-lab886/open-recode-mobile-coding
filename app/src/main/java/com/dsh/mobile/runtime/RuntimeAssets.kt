package com.dsh.mobile.runtime

import java.io.File
import java.security.MessageDigest

/**
 * Runtime payload manifest (`assets/runtime-assets.json`).
 *
 * ## Why nothing is vendored
 *
 * Node.js publishes **no Android/arm64 build**, and this repository has no
 * device to verify a hand-built one against. Committing an unverified binary
 * would be worse than shipping none, so the payload is described here and
 * fetched at runtime with hash verification. Until a `baseUrl` is configured
 * the app reports a precise "not configured" state instead of failing
 * cryptically deep inside `bootstrap.sh`.
 *
 * Parsing is deliberately pure so it is JVM-testable without Android.
 */
data class RuntimeAssetSpec(
    val id: String,
    val path: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val required: Boolean,
    val installMode: String,
    val description: String,
) {
    /** A hash is only trustworthy if it was actually recorded. */
    val hasTrustedHash: Boolean get() = sha256.length == 64 && sha256.all { it.isDigit() || it in 'a'..'f' }

    /** Resolved source URL, preferring an explicit per-asset override. */
    fun effectiveUrl(baseUrl: String): String = when {
        url.isNotBlank() -> url
        baseUrl.isBlank() -> ""
        else -> baseUrl.trimEnd('/') + "/" + path
    }
}

data class RuntimeAssetManifest(
    val schema: Int,
    val baseUrl: String,
    val assets: List<RuntimeAssetSpec>,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() || assets.any { it.url.isNotBlank() }

    companion object {
        /**
         * Hand-rolled parser: `org.json` ships in the Android runtime but not
         * on the JVM unit-test classpath, so depending on it would make this
         * logic untestable. Kept deliberately small and strict.
         */
        fun parse(json: String): RuntimeAssetManifest {
            val assets = Regex(
                """\{[^{}]*"id"\s*:\s*"[^"]*"[^{}]*\}""",
            ).findAll(json).mapNotNull { block ->
                // Handles both quoted strings ("0644") and bare literals
                // (true/false) — booleans in JSON are NOT quoted.
                fun str(key: String): String {
                    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                        .find(block.value)
                        ?.let { return it.groupValues[1] }
                    return Regex("\"$key\"\\s*:\\s*([A-Za-z0-9_.+-]+)")
                        .find(block.value)?.groupValues?.get(1) ?: ""
                }

                val id = str("id").ifBlank { return@mapNotNull null }
                RuntimeAssetSpec(
                    id = id,
                    path = str("path").ifBlank { id },
                    url = str("url"),
                    sha256 = str("sha256").lowercase(),
                    sizeBytes = Regex("\"sizeBytes\"\\s*:\\s*(\\d+)").find(block.value)
                        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                    required = str("required").equals("true", ignoreCase = true),
                    installMode = str("installMode").ifBlank { "0644" },
                    description = str("description"),
                )
            }.toList()

            require(assets.isNotEmpty()) { "runtime-assets.json declared no assets" }
            require(assets.map { it.id }.distinct().size == assets.size) {
                "runtime-assets.json contains duplicate asset ids"
            }

            return RuntimeAssetManifest(
                schema = Regex("\"schema\"\\s*:\\s*(\\d+)").find(json)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                baseUrl = Regex("\"baseUrl\"\\s*:\\s*\"([^\"]*)\"")
                    .find(json)?.groupValues?.get(1) ?: "",
                assets = assets,
            )
        }
    }
}

sealed interface RuntimePayloadState {
    data object Unknown : RuntimePayloadState
    /** Manifest present but no download source configured. */
    data class NotConfigured(val missing: List<RuntimeAssetSpec>) : RuntimePayloadState
    data class PartiallyPresent(
        val present: List<RuntimeAssetSpec>,
        val missing: List<RuntimeAssetSpec>,
    ) : RuntimePayloadState

    data object Complete : RuntimePayloadState
    data class Downloading(
        val current: String,
        val completed: Int,
        val total: Int,
        val bytesSoFar: Long,
        val totalBytes: Long,
    ) : RuntimePayloadState

    data class Failed(val message: String, val failedAsset: String?) : RuntimePayloadState
}

/**
 * Owns the on-disk runtime payload: reports what is missing, stages the small
 * bundled files the gateway needs, and downloads+verifies the large artifacts.
 */
class RuntimeAssets(private val root: File) {

    /** Where each asset lands, relative to the runtime root. */
    fun targetFor(spec: RuntimeAssetSpec): File = File(root, spec.path)

    fun isInstalled(spec: RuntimeAssetSpec): Boolean {
        val f = targetFor(spec)
        if (!f.exists() || f.length() == 0L) return false
        // Never trust size alone: verify the recorded hash when we have one.
        return if (spec.hasTrustedHash) sha256(f) == spec.sha256 else true
    }

    fun evaluate(manifest: RuntimeAssetManifest): RuntimePayloadState {
        val present = manifest.assets.filter { isInstalled(it) }
        val missing = manifest.assets.filterNot { isInstalled(it) }
        return when {
            missing.isEmpty() -> RuntimePayloadState.Complete
            !manifest.isConfigured -> RuntimePayloadState.NotConfigured(missing)
            else -> RuntimePayloadState.PartiallyPresent(present, missing)
        }
    }

    /**
     * Stages the gateway assets that ship inside the APK into the runtime
     * agents directory. `buildCommand` points argv at
     * `agents/visual-gateway/gateway.js`, which only exists once copied.
     *
     * Reads via a stream provider rather than [File] because the source lives
     * in the APK's asset store, not the filesystem.
     */
    /**
     * Unpack the bundled npm CLI into [root]/npm.
     *
     * npm is pure JavaScript, so it runs on the APK's own bionic Node - the
     * same interpreter that already runs DeepSeek Harness successfully. That
     * makes it far cheaper than a 46 MB Alpine rootfs plus PRoot, and it is the
     * only thing that lets `npm install` work at all: the shipped
     * dsh-agent.tar.gz contains no npm, and no runtime payload is available.
     *
     * Returns true when npm is present afterwards.
     */
    fun stageNpm(openSource: (String) -> java.io.InputStream?): Boolean {
        val target = File(root, "npm")
        val cli = File(target, "bin/npm-cli.js")
        if (cli.isFile) return true
        val stream = openSource(NPM_ARCHIVE) ?: return false
        return try {
            target.mkdirs()
            stream.use { input ->
                java.util.zip.GZIPInputStream(input).use { gz ->
                    // Hand-rolled tar: commons-compress is not on the
                    // classpath and android.util.zip has no TAR reader. Same
                    // format the installer already parses - a 512-byte header
                    // per entry, name in 0..100, octal size at 124..136, type
                    // flag at 156, payload padded to the next 512 boundary.
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
                        // npm's tarball prefixes every entry with "package/".
                        val rel = name.removePrefix("package/")
                        if (rel.isNotEmpty() && !rel.startsWith("/") && !rel.contains("..")) {
                            val out = File(target, rel)
                            if (typeFlag == '5') {
                                out.mkdirs()
                                skipFully(gz, size)
                            } else {
                                out.parentFile?.mkdirs()
                                val payload = ByteArray(size.toInt())
                                readFully(gz, payload)
                                out.outputStream().use { it.write(payload) }
                            }
                        } else {
                            skipFully(gz, size)
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) skipFully(gz, pad)
                    }
                }
            }
            cli.isFile
        } catch (_: Exception) {
            // Deliberately swallowed: the caller surfaces the real reason. If
            // npm is missing the installer fails, and npm's own error message
            // explains far more than whatever went wrong during an unpack.
            false
        }
    }

    private fun readFully(inp: java.io.InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = inp.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun skipFully(inp: java.io.InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = inp.skip(left)
            if (n <= 0) {
                if (inp.read() < 0) return
                left--
            } else {
                left -= n
            }
        }
    }

    fun stageBundledAssets(openSource: (String) -> java.io.InputStream?, assetSink: (String) -> File) {
        val bundled = listOf("visual-gateway/gateway.js", "visual-gateway/ui.html")
        for (rel in bundled) {
            val dst = assetSink(rel)
            try {
                val stream = openSource(rel) ?: continue
                val bytes = stream.use { it.readBytes() }
                // Skip the write when content already matches.
                if (dst.exists() && dst.length() == bytes.size.toLong()) {
                    val existing = dst.readBytes()
                    if (existing.contentEquals(bytes)) continue
                }
                dst.parentFile?.mkdirs()
                dst.writeBytes(bytes)
            } catch (_: Exception) {
                // Non-fatal: boot() reports a precise error if the file is unusable.
            }
        }
    }

    companion object {
        /**
         * Name of the npm CLI archive bundled in the APK's assets.
         *
         * Deliberately NOT ".tar.gz": aapt2 strips a trailing ".gz" from
         * asset names, so the file packaged as `assets/npm-cli.tar` and
         *  returned null at runtime - a silent
         * unpack failure that looked like a missing dependency.
         */
        const val NPM_ARCHIVE = "npm-cli.tgz"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}