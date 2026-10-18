package com.dsh.mobile.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug behind "it loads the whole environment once again".
 *
 * `MainActivity.onStart()` unconditionally sent `ACTION_BOOT`, and `onStart()`
 * fires on every return to the app - switching to another app and coming back
 * included. `boot()` begins by cancelling the running job and killing the child
 * process, so the first thing the app did on regaining focus was destroy the
 * session the user left running and rebuild it from scratch. It reads as "the
 * app cannot keep a session alive".
 *
 * The service is a sticky foreground service that deliberately outlives the
 * Activity, so the work was never actually at risk. We were throwing it away.
 *
 * These tests are source-contract tests rather than JVM unit tests of the
 * service itself, because the behaviour lives in the interaction between the
 * Activity's lifecycle and the service's boot path - and that interaction is
 * exactly what regressed.
 */
class ServiceBootIdempotencyTest {

    private val service: String by lazy { read("src/main/java/com/dsh/mobile/service/AgentRuntimeService.kt") }
    private val activity: String by lazy { read("src/main/java/com/dsh/mobile/MainActivity.kt") }

    private fun read(path: String): String {
        val f = java.io.File(path)
        return stripComments(if (f.exists()) f.readText() else java.io.File("app/$path").readText())
    }

    /**
     * Remove comments before asserting on source text.
     *
     * These files document the bug at length, and the documentation mentions
     * "ACTION_BOOT", "boot()" and "stopChild" constantly. Asserting on the raw
     * text matches the explanation of the bug rather than the fix, and fails
     * against correct code - so the comments have to come out first.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i)
                    i = if (end == -1) source.length else end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end == -1) source.length else end + 2
                }
                else -> {
                    out.append(source[i]); i++
                }
            }
        }
        return out.toString()
    }

    /**
     * The core regression: returning to the app must re-attach, never re-boot.
     */
    @Test
    fun `returning to the app must not request a reboot`() {
        val onStart = activity.substringAfter("override fun onStart()")
            .substringBefore("override fun onDestroy()")

        assertTrue(
            "onStart must send ACTION_ATTACH to re-observe without restarting:\n$onStart",
            onStart.contains("ACTION_ATTACH"),
        )
        // ACTION_BOOT is still correct on the FIRST create - there is nothing
        // running to lose then. The bug was sending it on every foreground.
        // What matters is that it is guarded and that the other branch attaches.
        val bootIdx = onStart.indexOf("ACTION_BOOT")
        val attachIdx = onStart.indexOf("ACTION_ATTACH")
        assertTrue("onStart should still boot once on first create", bootIdx >= 0)
        assertTrue(
            "the non-first-start branch must attach, not boot:\n$onStart",
            attachIdx > bootIdx,
        )
        assertTrue(
            "the boot must be guarded by the one-shot flag:\n$onStart",
            onStart.lastIndexOf("agentBootRequested", bootIdx) >= 0,
        )
    }

    /**
     * Boot is requested exactly once per Activity lifetime. The flag is an
     * instance field on purpose: it guards Activity recreation (rotation,
     * "don't keep activities"), not process lifetime, because after process
     * death the sticky service boots itself from a null intent.
     */
    @Test
    fun `boot is requested exactly once per activity lifetime`() {
        val onStart = activity.substringAfter("override fun onStart()")
            .substringBefore("override fun onDestroy()")

        assertTrue(
            "onStart must be guarded by a one-shot flag:\n$onStart",
            onStart.contains("agentBootRequested"),
        )
        val flagDecl = activity.substringBefore("override fun onStart()")
        assertTrue(
            "the flag must be a mutable instance field",
            flagDecl.contains("private var agentBootRequested"),
        )
    }

    /**
     * The service is the second line of defence. Even if the Activity asks to
     * boot, a healthy child already serving the requested agent/workspace/port
     * must be left alone - otherwise any other caller reintroduces the bug.
     */
    @Test
    fun `action_boot is idempotent in the service itself`() {
        val dispatch = service.substringAfter("override fun onStartCommand")
            .substringBefore("return START_STICKY")

        val branch = dispatch.substringAfter("ACTION_BOOT ->")
            .substringBefore("ACTION_RESTART")
        assertTrue(
            "ACTION_BOOT must consult isAlreadyServing() before booting:\n$branch",
            branch.contains("isAlreadyServing()"),
        )
        // The only boot() reachable from ACTION_BOOT is the guarded else.
        assertTrue(
            "ACTION_BOOT must boot only when not already serving:\n$branch",
            branch.replace(" ", "").contains("if(isAlreadyServing())refreshStatus()elseboot()"),
        )
    }

    /**
     * A restart is the explicit escape hatch and must still always take effect,
     * otherwise the idempotency guard would make it impossible to recover a
     * wedged agent.
     */
    @Test
    fun `an explicit restart always reboots`() {
        val dispatch = service.substringAfter("override fun onStartCommand")
            .substringBefore("return START_STICKY")
        // Scoped to ACTION_RESTART's own branch: the invariant is that it calls
        // boot() WITHOUT the idempotency guard, not that it is written on one
        // line. The branch also resets the stratified-restart budget now, so
        // pinning the one-liner form would fail for a cosmetic reason.
        val branch = dispatch.substringAfter("ACTION_RESTART ->")
            .substringBefore("ACTION_ATTACH ->")
        assertTrue(
            "ACTION_RESTART must call boot():\n$branch",
            Regex("""\bboot\(\)""").containsMatchIn(branch),
        )
        assertFalse(
            "ACTION_RESTART must bypass the idempotency guard:\n$branch",
            branch.contains("isAlreadyServing"),
        )
    }

    /**
     * `isAlreadyServing` must compare the live config, not just "is a child
     * alive". A child running the right agent against the WRONG workspace is
     * still wrong, and a pure liveness check would keep it.
     */
    @Test
    fun `the guard compares agent and workspace not just liveness`() {
        val guard = service.substringAfter("private suspend fun isAlreadyServing")
            .substringBefore("private fun refreshStatus")
        assertTrue("must check child liveness", guard.contains("child?.isAlive"))
        assertTrue("must check the status is Running", guard.contains("ServiceStatus.Running"))
        assertTrue("must compare the agent", guard.contains("status.agent == desiredAgent"))
        assertTrue("must compare the workspace", guard.contains("status.workspace"))
    }

    /**
     * The watchdog owns recovery of a genuinely dead child. The idempotency
     * guard must not disable it, or a crashed agent would stay dead forever.
     */
    @Test
    fun `watchdog recovery still owns a genuinely dead child`() {
        val attach = service.substringAfter("ACTION_ATTACH ->")
            .substringBefore("ACTION_STOP ->")
        assertFalse(
            "ACTION_ATTACH must not boot: $attach",
            attach.contains("boot()"),
        )
        assertFalse(
            "ACTION_ATTACH must not kill the child: $attach",
            attach.contains("stopChild"),
        )
    }

    /**
     * After process death Android restarts a sticky service with a null intent.
     * With no Activity alive nothing would ever boot the agent again, so it
     * would sit Idle forever - the mirror image of the bug above.
     */
    @Test
    fun `a null sticky-restart intent still boots`() {
        val dispatch = service.substringAfter("override fun onStartCommand")
            .substringBefore("return START_STICKY")
        assertTrue(
            "a null intent (Android's sticky restart) must boot:\n$dispatch",
            dispatch.contains("null -> boot()"),
        )
    }

    /**
     * The service must remain sticky and must not be stopped by the Activity,
     * otherwise backgrounding the app ends the agent no matter what else is
     * correct.
     */
    @Test
    fun `the service stays sticky and is not stopped on activity destroy`() {
        assertTrue("the service must remain START_STICKY", service.contains("START_STICKY"))
        val onDestroy = activity.substringAfter("override fun onDestroy()")
        assertFalse(
            "the Activity must not stop the service on destroy:\n$onDestroy",
            onDestroy.contains("stopService") || onDestroy.contains("ACTION_STOP"),
        )
    }
}