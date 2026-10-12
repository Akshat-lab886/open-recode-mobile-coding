package com.dsh.mobile.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Decides how many agents this device can actually run at once.
 *
 * ## Why measured, not a constant
 *
 * "Run two agents in parallel" cannot be a fixed number. On this device
 * (8 cores, 3.7 GB RAM) two is comfortable and three is not; on a 2 GB phone
 * one is the honest answer. A hardcoded cap either wastes a capable device or
 * overcommits a weak one and gets the whole runtime OOM-killed — which would
 * lose the user's work, the exact thing the session-resume fix exists to
 * prevent.
 *
 * ## The binding constraint on a phone is storage, not RAM
 *
 * Each agent is a Node process with its own package tree (180-260 MB on disk)
 * plus a resident set of a few hundred MB. Disk is the first thing to run out,
 * and when it does the runtime stops working. So [maxConcurrentAgents] weighs
 * both, and the tighter one wins.
 *
 * ## Honest about measurement error
 *
 * These are point-in-time readings. [StorageInfo] is a snapshot, so the answer
 * can be stale by the time it is used; that is why the free-space requirement
 * has a deliberate margin below what is actually free.
 */
object DeviceCapacity {

    data class StorageInfo(
        val freeBytes: Long,
        val totalBytes: Long,
    ) {
        val freeMegabytes: Long get() = freeBytes / (1024 * 1024)
    }

    data class MemoryInfo(
        val totalBytes: Long,
        val availableBytes: Long,
    ) {
        val availableMegabytes: Long get() = availableBytes / (1024 * 1024)
        val totalMegabytes: Long get() = totalBytes / (1024 * 1024)
    }

    data class Capacity(
        val cores: Int,
        val memory: MemoryInfo,
        val storage: StorageInfo,
        val maxConcurrent: Int,
        /** Why the limit is what it is - surfaced in the UI. */
        val reason: String,
    )

    /**
     * Bytes of free disk required before starting one more agent.
     *
     * Deliberately larger than a single agent's install size: the agent needs
     * room for its package tree, npm's cache, and the transcript that the
     * session log will write. Half a gigabyte leaves room for all three.
     */
    const val FREE_BYTES_PER_AGENT = 512L * 1024 * 1024

    /**
     * Resident memory required per running agent.
     *
     * A Node CLI plus its agent session is comfortably over 300 MB once warm;
     * 600 MB is used so the cap reacts before the system starts killing
     * processes under pressure.
     */
    const val MEMORY_PER_AGENT = 600L * 1024 * 1024

    /** Absolute ceiling regardless of how capable the device looks. */
    const val MAX_ALLOWED = 4

    fun readStorage(path: String): StorageInfo = runCatching {
        val stat = StatFs(path)
        StorageInfo(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.getOrDefault(StorageInfo(0, 0))

    fun readMemory(context: Context): MemoryInfo = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // availMem (not freeMem) is what can actually be handed out now:
        // freeMem excludes reclaimable cache and badly understates the real
        // headroom. Verified against android-36's MemoryInfo, which exposes
        // availMem/totalMem/lowMemory and no availableMem field.
        @Suppress("DEPRECATION")
        val avail = info.availMem
        MemoryInfo(totalBytes = info.totalMem, availableBytes = avail)
    }.getOrDefault(MemoryInfo(0, 0))

    fun readCores(): Int = Runtime.getRuntime().availableProcessors()

    fun inspect(context: Context): Capacity {
        val cores = readCores()
        val memory = readMemory(context)
        val storage = readStorage(context.filesDir.absolutePath)
        val decision = decide(cores, memory, storage)
        return Capacity(cores, memory, storage, decision.first, decision.second)
    }

    /**
     * The actual rule, as a pure function so it can be tested on the JVM.
     *
     * Returns the cap and a human-readable reason. Three limits are applied and
     * the smallest wins: cores (agents are I/O-ish but do compile and npm work),
     * memory, and free disk.
     */
    fun decide(
        cores: Int,
        memory: MemoryInfo,
        storage: StorageInfo,
        perAgentBytes: Long = FREE_BYTES_PER_AGENT,
        memPerAgent: Long = MEMORY_PER_AGENT,
    ): Pair<Int, String> {
        if (cores <= 0 || memory.totalBytes <= 0L || storage.totalBytes <= 0L) {
            // Could not measure. One agent is the only safe assumption, and
            // guessing higher could OOM the runtime.
            return 1 to "Could not measure this device; allowing one agent."
        }

        val byCores = cores / 2
        val byMemory = (memory.availableBytes / memPerAgent).toInt()
        val byStorage = (storage.freeBytes / perAgentBytes).toInt()

        val limit = minOf(byCores, byMemory, byStorage, MAX_ALLOWED).coerceAtLeast(1)

        val reason = when (limit) {
            byStorage -> "Limited by storage: ${storage.freeMegabytes} MB free " +
                "(${perAgentBytes / (1024 * 1024)} MB needed per agent)."
            byMemory -> "Limited by memory: ${memory.availableMegabytes} MB available " +
                "(${memPerAgent / (1024 * 1024)} MB per agent)."
            byCores -> "Limited by CPU: $cores cores."
            else -> "$limit concurrent agents supported."
        }
        return limit to reason
    }

    /**
     * Whether one more agent can be started right now.
     *
     * Used before spawning, so the user gets a clear message instead of a
     * half-started agent that immediately dies.
     */
    fun canStartAnother(context: Context, alreadyRunning: Int): Boolean {
        val cap = inspect(context).maxConcurrent
        return alreadyRunning < cap
    }
}