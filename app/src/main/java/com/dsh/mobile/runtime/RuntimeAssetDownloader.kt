package com.dsh.mobile.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Fetches the runtime payload described by a [RuntimeAssetManifest].
 *
 * Guarantees that matter:
 *  - every download lands in a `.part` temp file and is only moved into place
 *    after its SHA-256 matches, so a killed process can never leave a
 *    truncated binary that later looks "installed";
 *  - a missing or malformed hash is refused rather than silently trusted;
 *  - cancellation propagates instead of being swallowed by the IO loop.
 */
class RuntimeAssetDownloader(
    private val assets: RuntimeAssets,
    private val onProgress: (RuntimePayloadState) -> Unit = {},
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    suspend fun install(manifest: RuntimeAssetManifest): RuntimePayloadState =
        withContext(Dispatchers.IO) {
            if (!manifest.isConfigured) {
                val missing = manifest.assets.filterNot { assets.isInstalled(it) }
                return@withContext RuntimePayloadState.NotConfigured(missing)
            }

            val pending = manifest.assets.filterNot { assets.isInstalled(it) }
            if (pending.isEmpty()) return@withContext RuntimePayloadState.Complete

            // Size total only when known, so the progress bar stays honest.
            val totalBytes = pending.sumOf { it.sizeBytes }.takeIf { it > 0 } ?: 0L

            var done = 0
            var bytesSoFar = 0L

            for (spec in pending) {
                currentCoroutineContext().ensureActive()
                onProgress(
                    RuntimePayloadState.Downloading(spec.id, done, pending.size, bytesSoFar, totalBytes),
                )

                val url = spec.effectiveUrl(manifest.baseUrl)
                if (url.isBlank()) {
                    return@withContext RuntimePayloadState.Failed(
                        "No download URL for '${spec.id}'. Set a payload source in the Control Center.",
                        spec.id,
                    )
                }
                if (!spec.hasTrustedHash) {
                    // Verifying against a blank hash would accept anything.
                    return@withContext RuntimePayloadState.Failed(
                        "Asset '${spec.id}' has no valid sha256; refusing to install unverified bytes.",
                        spec.id,
                    )
                }

                try {
                    val bytes = download(url, spec)
                    val actual = RuntimeAssets.sha256(bytes)
                    if (!actual.equals(spec.sha256, ignoreCase = true)) {
                        return@withContext RuntimePayloadState.Failed(
                            "Checksum mismatch for '${spec.id}'\nexpected ${spec.sha256}\nactual   $actual",
                            spec.id,
                        )
                    }
                    val target = assets.targetFor(spec)
                    target.parentFile?.mkdirs()
                    // .part + rename keeps a half-written file from ever being
                    // mistaken for a complete install.
                    val part = File(target.absolutePath + ".part")
                    part.writeBytes(bytes)
                    if (!part.renameTo(target)) {
                        target.writeBytes(bytes)
                        part.delete()
                    }
                    target.setExecutable(spec.installMode == "0755", true)

                    done++
                    bytesSoFar += bytes.size
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    return@withContext RuntimePayloadState.Failed(
                        "Could not fetch '${spec.id}': ${e.message ?: e.javaClass.simpleName}",
                        spec.id,
                    )
                }
            }

            onProgress(RuntimePayloadState.Complete)
            RuntimePayloadState.Complete
        }

    private fun download(url: String, spec: RuntimeAssetSpec): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from ${spec.id}")
            }
            val declared = conn.contentLengthLong
            if (declared > 0 && spec.sizeBytes > 0 && declared != spec.sizeBytes) {
                throw IllegalStateException(
                    "size mismatch for '${spec.id}': server says $declared, manifest says ${spec.sizeBytes}",
                )
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}