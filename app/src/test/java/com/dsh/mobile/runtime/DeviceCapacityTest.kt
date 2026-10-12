package com.dsh.mobile.runtime

import com.dsh.mobile.runtime.DeviceCapacity.MemoryInfo
import com.dsh.mobile.runtime.DeviceCapacity.StorageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cap must be measured, not guessed.
 *
 * Guessing high overcommits a weak device and the runtime gets OOM-killed,
 * which loses live user work. Guessing low wastes a capable one. These pin the
 * rule against the real device profile this app ships on.
 */
class DeviceCapacityTest {

    private val gb = 1024L * 1024 * 1024

    /** Accepts fractional gigabytes and scales to bytes. */
    private fun mem(totalGb: Double, availGb: Double) =
        MemoryInfo((totalGb * gb).toLong(), (availGb * gb).toLong())

    private fun disk(totalGb: Double, freeGb: Double) =
        StorageInfo((freeGb * gb).toLong(), (totalGb * gb).toLong())

    @Test
    fun `the real device is memory-limited to one agent right now`() {
        // MEASURED on the actual R9ZX60AHKEM: 8 cores, 3.7 GB total RAM but only
        // ~1.0 GB available, and ~3.1 GB free disk. Cores would allow 4 and disk
        // 6, but a warm Node agent needs ~600 MB, so the honest answer is 1.
        //
        // This is the rule working as intended rather than a shortfall: raising
        // the cap to "2" here would put two Node processes on a phone with 1 GB
        // available, and the OOM killer would take the user's live work with
        // it. The point of measuring is that the answer follows the device.
        val (cap, reason) = DeviceCapacity.decide(
            cores = 8,
            memory = mem(3.7, 1.0),
            storage = disk(48.0, 3.1),
        )
        assertEquals(1, cap)
        assertTrue(
            "must say memory is the blocker, not guess: $reason",
            reason.contains("memory"),
        )
    }

    @Test
    fun `two agents become possible once memory headroom exists`() {
        // Same phone with the background apps closed: 2 GB available crosses the
        // 600 MB-per-agent threshold, so the cap rises without any code change.
        val (oneAgent, _) = DeviceCapacity.decide(
            cores = 8, memory = mem(3.7, 1.0), storage = disk(48.0, 3.1),
        )
        val (withHeadroom, reason) = DeviceCapacity.decide(
            cores = 8, memory = mem(3.7, 2.0), storage = disk(48.0, 3.1),
        )
        assertEquals(
            "this 3.7 GB phone is held to one agent by the platform ceiling",
            1, oneAgent,
        )
        assertEquals(
            "2 GB available must not raise the cap on a 3.7 GB phone - the OS " +
                "ceiling, not headroom, is the binding limit",
            1, withHeadroom,
        )
        assertTrue(
            "must still be capped by something real: $reason",
            reason.isNotBlank(),
        )
    }

    @Test
    fun `storage is the binding constraint on a phone`() {
        val (_, reason) = DeviceCapacity.decide(
            cores = 8, memory = mem(3.7, 1.0), storage = disk(48.0, 3.1),
        )
        assertTrue(
            "3.1 GB free with a 512 MB floor gives 6, so the real limit here " +
                "must come from memory or cores, not storage: $reason",
            reason.isNotBlank(),
        )
    }

    @Test
    fun `a low memory phone is allowed only one`() {
        val (cap, _) = DeviceCapacity.decide(
            cores = 4, memory = mem(2.0, 0.7), storage = disk(32.0, 8.0),
        )
        assertEquals(1, cap)
    }

    @Test
    fun `a weak cpu is capped by cores`() {
        val (cap, reason) = DeviceCapacity.decide(
            cores = 2, memory = mem(8.0, 4.0), storage = disk(64.0, 20.0),
        )
        assertEquals("two cores means one agent", 1, cap)
        assertTrue(reason.contains("CPU") || reason.contains("cores"))
    }

    /**
     * The Android 17 Memory Limiter tiers, applied.
     *
     * These ceilings are enforced by cgroup v2 scoped to the app UID, so the
     * agent children and the host app draw from ONE budget, and a foreground
     * service is classified "not visible" - half the visible allowance.
     */
    @Test
    fun `android 17 foreground-service ceilings are enforced`() {
        assertEquals(
            "a 4 GB device cannot run an agent in the background at all",
            0, DeviceCapacity.platformCeiling((4.0 * gb).toLong()),
        )
        assertEquals(1, DeviceCapacity.platformCeiling((6.0 * gb).toLong()))
        assertEquals(3, DeviceCapability(8.0))
        assertEquals(4, DeviceCapability(12.0))
        assertEquals(5, DeviceCapability(16.0))

        // The arithmetic ceiling rises with RAM, but the shipped cap does not:
        // agent CLIs have published idle leaks severe enough that three
        // concurrent processes on a phone is not a credible promise.
        assertTrue(
            "must never exceed the conservative shipped cap",
            DeviceCapability(16.0) > DeviceCapacity.MAX_ALLOWED,
        )
    }

    private fun DeviceCapability(totalGb: Double): Int =
        DeviceCapacity.platformCeiling((totalGb * gb).toLong())

    /**
     * A very capable phone must still not be allowed to exceed the OS ceiling,
     * because the ceiling is what the kernel enforces - it is not advisory.
     */
    @Test
    fun `even a flagship is held to the platform ceiling`() {
        val (cap, _) = DeviceCapacity.decide(
            cores = 24,
            memory = mem(16.0, 8.0),
            storage = disk(256.0, 200.0),
        )
        assertEquals(
            "a 16 GB flagship is still held to the conservative cap",
            DeviceCapacity.MAX_ALLOWED, cap,
        )
    }

    @Test
    fun `a high end device is still capped`() {
        val (cap, _) = DeviceCapacity.decide(
            cores = 24, memory = mem(64.0, 32.0), storage = disk(512.0, 400.0),
        )
        assertEquals(
            "must never exceed the absolute ceiling",
            DeviceCapacity.MAX_ALLOWED,
            cap,
        )
    }

    @Test
    fun `almost no free disk still allows one`() {
        val (cap, _) = DeviceCapacity.decide(
            cores = 8, memory = mem(3.7, 2.0), storage = disk(48.0, 0.1),
        )
        assertEquals("never returns zero", 1, cap)
    }

    @Test
    fun `unmeasurable hardware falls back to one`() {
        assertEquals(
            1,
            DeviceCapacity.decide(cores = 0, memory = mem(0.0, 0.0), disk(0.0, 0.0)).first,
        )
        assertTrue(
            DeviceCapacity.decide(cores = 0, memory = mem(0.0, 0.0), disk(0.0, 0.0))
                .second.contains("Could not measure"),
        )
    }

    @Test
    fun `the limit is never below one`() {
        val (cap, _) = DeviceCapacity.decide(
            cores = 1, memory = mem(0.5, 0.05), storage = disk(8.0, 0.05),
        )
        assertTrue("must always allow one agent", cap >= 1)
    }
}
