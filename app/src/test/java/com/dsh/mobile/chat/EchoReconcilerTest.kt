package com.dsh.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoReconcilerTest {

    @Test
    fun `an expected echo is suppressed so the bubble is not doubled`() {
        val r = EchoReconciler()
        r.expectEcho("fix the build")
        assertFalse(r.onEcho("fix the build"))
        assertEquals(1, r.suppressedCount())
    }

    @Test
    fun `an unexpected echo is shown`() {
        // Sent from the WebView, so the native surface has never seen it.
        assertTrue(EchoReconciler().onEcho("sent from the web ui"))
    }

    @Test
    fun `several sends reconcile in order`() {
        val r = EchoReconciler()
        r.expectEcho("one")
        r.expectEcho("two")
        assertFalse(r.onEcho("one"))
        assertFalse(r.onEcho("two"))
        assertEquals(2, r.suppressedCount())
    }

    @Test
    fun `the same text sent twice is suppressed twice`() {
        val r = EchoReconciler()
        r.expectEcho("test")
        r.expectEcho("test")
        assertFalse(r.onEcho("test"))
        assertFalse(r.onEcho("test"))
        // A third, unsent echo is genuinely new and must be shown.
        assertTrue(r.onEcho("test"))
    }

    @Test
    fun `an echo arriving out of order still reconciles`() {
        val r = EchoReconciler()
        r.expectEcho("first")
        r.expectEcho("second")
        assertFalse(r.onEcho("second"))
        assertFalse(r.onEcho("first"))
        assertEquals(2, r.suppressedCount())
    }

    @Test
    fun `expectations do not leak past the bound`() {
        // A send whose echo never arrives must not swallow a real echo much
        // later - that would silently hide a message the user sent elsewhere.
        val r = EchoReconciler(maxPending = 2)
        r.expectEcho("lost-1")
        r.expectEcho("lost-2")
        r.expectEcho("lost-3")
        // lost-1 was evicted, so its late echo is shown rather than swallowed.
        assertTrue(r.onEcho("lost-1"))
    }

    @Test
    fun `clear forgets pending and counters`() {
        val r = EchoReconciler()
        r.expectEcho("x")
        assertFalse(r.onEcho("x"))
        r.clear()
        assertEquals(0, r.suppressedCount())
        assertTrue(r.onEcho("x"))
    }
}
