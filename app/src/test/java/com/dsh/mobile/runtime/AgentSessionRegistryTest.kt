package com.dsh.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capacity gating for concurrent agent sessions.
 *
 * The rule under test is deliberately blunt: when the device cannot afford
 * another agent, the registry refuses rather than trying and letting the kernel
 * kill a process. On Android 17 an over-committed app is not throttled, it is
 * terminated, and the user's in-flight session dies with it.
 */
class AgentSessionRegistryTest {

    private fun registry() = AgentSessionRegistry(portRangeStart = 3100, portRangeEnd = 3102)

    @Test
    fun `first session is accepted and gets the lowest free port`() {
        val r = registry()
        val s = r.start("a", "dsh", "/ws", allowed = 2).getOrThrow()
        assertEquals("dsh", s.agentId)
        assertEquals(3100, s.port)
        assertEquals(1, r.count)
    }

    @Test
    fun `concurrent sessions get distinct ports`() {
        val r = registry()
        val a = r.start("a", "dsh", "/ws", allowed = 2).getOrThrow()
        val b = r.start("b", "claude", "/ws", allowed = 2).getOrThrow()
        assertTrue("ports must not collide", a.port != b.port)
        assertEquals(2, r.count)
    }

    @Test
    fun `exceeding the capacity allowance is refused`() {
        val r = registry()
        r.start("a", "dsh", "/ws", allowed = 1).getOrThrow()
        val second = r.start("b", "claude", "/ws", allowed = 1)
        assertTrue(second.isFailure)
        assertEquals(
            SessionRejection.AtCapacity(running = 1, allowed = 1),
            (second.exceptionOrNull() as AgentSessionRegistry.SessionRefusal).reason,
        )
        assertEquals("a refused start must not be recorded", 1, r.count)
    }

    @Test
    fun `a device limited to one agent cannot start two`() {
        // This is the real measured case for the test handset: 3.7 GB total,
        // ~1.1 GB available. The gate legitimately allows exactly one.
        val allowed = DeviceCapacity.decide(
            cores = 8,
            memory = DeviceCapacity.MemoryInfo(
                availableBytes = 1_130_000_000L,
                totalBytes = 3_730_404_000L,
            ),
            storage = DeviceCapacity.StorageInfo(
                freeBytes = 3_300_000_000L,
                totalBytes = 48_000_000_000L,
            ),
        ).first
        assertEquals(1, allowed)

        val r = registry()
        r.start("a", "dsh", "/ws", allowed = allowed).getOrThrow()
        assertTrue(
            "the second agent must be refused on this handset",
            r.start("b", "claude", "/ws", allowed = allowed).isFailure,
        )
    }

    @Test
    fun `duplicate session ids are rejected`() {
        val r = registry()
        r.start("a", "dsh", "/ws", allowed = 3).getOrThrow()
        val again = r.start("a", "codex", "/ws", allowed = 3)
        assertTrue(again.isFailure)
        assertEquals(
            SessionRejection.DuplicateId("a"),
            (again.exceptionOrNull() as AgentSessionRegistry.SessionRefusal).reason,
        )
    }

    @Test
    fun `an occupied port is skipped`() {
        val r = registry()
        val s = r.start("a", "dsh", "/ws", allowed = 2, isPortFree = { it != 3100 })
            .getOrThrow()
        assertEquals(3101, s.port)
    }

    @Test
    fun `exhausting the port range is reported, not silently reaped`() {
        val r = registry()
        // The range is 3100..3102, so three sessions fit and the fourth cannot.
        // Capacity must NOT be the reason it fails - the ports are gone.
        r.start("a", "dsh", "/ws", allowed = 5).getOrThrow()
        r.start("b", "claude", "/ws", allowed = 5).getOrThrow()
        r.start("c", "codex", "/ws", allowed = 5).getOrThrow()
        val fourth = r.start("d", "opencode", "/ws", allowed = 5)
        assertTrue(fourth.isFailure)
        assertEquals(
            SessionRejection.NoFreePort(3100, 3102),
            (fourth.exceptionOrNull() as AgentSessionRegistry.SessionRefusal).reason,
        )
        assertEquals("no session may be recorded when ports run out", 3, r.count)
    }

    @Test
    fun `stopping frees capacity for the next agent`() {
        val r = registry()
        r.start("a", "dsh", "/ws", allowed = 1).getOrThrow()
        assertTrue(r.start("b", "claude", "/ws", allowed = 1).isFailure)

        assertEquals("dsh", r.stop("a")?.agentId)
        assertEquals(0, r.count)

        r.start("b", "claude", "/ws", allowed = 1).getOrThrow()
        assertEquals(1, r.count)
    }

    @Test
    fun `stopping an unknown session returns null`() {
        assertNull(registry().stop("nope"))
    }

    @Test
    fun `stopAll clears everything`() {
        val r = registry()
        r.start("a", "dsh", "/ws", allowed = 3).getOrThrow()
        r.start("b", "claude", "/ws", allowed = 3).getOrThrow()
        assertEquals(2, r.stopAll().size)
        assertEquals(0, r.count)
        assertTrue(r.running.isEmpty())
    }

    @Test
    fun `the same agent is not run twice`() {
        val r = registry()
        r.start("a", "dsh", "/ws", allowed = 3).getOrThrow()
        assertTrue(r.hasAgent("dsh"))
        assertFalse(r.hasAgent("claude"))
    }

    @Test
    fun `a released port is reused by the next session`() {
        val r = registry()
        val a = r.start("a", "dsh", "/ws", allowed = 1).getOrThrow()
        r.stop("a")
        val b = r.start("b", "claude", "/ws", allowed = 1).getOrThrow()
        assertEquals(a.port, b.port)
    }
}