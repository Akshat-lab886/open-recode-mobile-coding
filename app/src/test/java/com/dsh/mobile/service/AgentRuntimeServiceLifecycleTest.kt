package com.dsh.mobile.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract tests for the runtime service's lifecycle contract.
 *
 * The bug these pin down: `MainActivity.onStart()` used to send `ACTION_BOOT`
 * on every foreground. `onStart()` runs on every return to the app - switching
 * to another app and coming back included - and `boot()` begins by cancelling
 * the running job and killing the child process. So the app destroyed live
 * agent work and reloaded the entire environment behind the user's back.
 *
 * The service is a sticky foreground service that deliberately outlives the
 * Activity, so the work was never actually at risk. The failure was entirely
 * self-inflicted.
 */
class AgentRuntimeServiceLifecycleTest {

    private val service: String by lazy {
        File("src/main/java/com/dsh/mobile/service/AgentRuntimeService.kt")
            .let { if (it.exists()) it.readText() else File("app/src/main/java/com/dsh/mobile/service/AgentRuntimeService.kt").readText() }
    }

    private val activity: String by lazy {
        File("src/main/java/com/dsh/mobile/MainActivity.kt")
            .let { if (it.exists()) it.readText() else File("app/src/main/java/com/dsh/mobile/MainActivity.kt").readText() }
    }

    /**
     * The core regression: returning to the app must re-attach, not reboot.
     */
    @Test
    fun `returning to the app does not request a reboot`() {
        val onStart = activity.substringAfter("override fun onStart()")
            .substringBefore("override fun onDestroy()")

        // The invariant: ACTION_BOOT may only appear inside the one-shot guard,
        // and the other branch must re-attach. An unguarded ACTION_BOOT - the
        // original bug - is unreachable through this test.
        val bootIdx = onStart.indexOf("AgentRuntimeService.ACTION_BOOT")
        assertTrue(
            "onStart must still request a boot on first create: $onStart",
            bootIdx >= 0,
        )
        val guardIdx = onStart.indexOf("if (!agentBootRequested)")
        assertTrue(
            "ACTION_BOOT must sit inside the one-shot guard: $onStart",
            guardIdx in 0 until bootIdx,
        )
        assertTrue(
            "the non-first-start branch must re-attach: $onStart",
            onStart.substring(bootIdx).contains("ACTION_ATTACH"),
        )
    }

    @Test
    fun `boot is requested exactly once per Activity lifetime`() {
        val onStart = activity.substringAfter("override fun onStart()")
            .substringBefore("override fun onDestroy()")
        assertTrue(
            "boot must be guarded by a one-shot flag so Activity recreation " +
                "does not double-boot: $onStart",
            onStart.contains("agentBootRequested"),
        )
    }

    /**
     * ACTION_ATTACH must be observation-only. If it ever calls boot() it would
     * reintroduce the exact bug above, just from a different caller.
     */
    @Test
    fun `attach never boots or kills the child`() {
        val branch = service.substringAfter("ACTION_ATTACH ->")
            .substringBefore("ACTION_STOP ->")
        assertTrue(
            "ACTION_ATTACH must not boot: $branch",
            !branch.contains("boot()"),
        )
        assertTrue(
            "ACTION_ATTACH must not kill the child: $branch",
            !branch.contains("stopChild"),
        )
    }

    /**
     * After process death Android restarts a sticky service with a null
     * intent. With no Activity left alive nothing would ever boot the agent
     * again, so it would sit Idle forever - the mirror image of the bug above.
     */
    @Test
    fun `a null sticky-restart intent still boots`() {
        val dispatch = service.substringAfter("override fun onStartCommand")
            .substringBefore("return START_STICKY")
        assertTrue(
            "a null intent (Android's sticky restart) must boot, or the agent " +
                "never returns after process death: $dispatch",
            dispatch.contains("null -> boot()"),
        )
    }

    @Test
    fun `the service stays sticky so work outlives the Activity`() {
        assertTrue("the service must remain START_STICKY", service.contains("START_STICKY"))
        val onDestroy = activity.substringAfter("override fun onDestroy()")
        assertFalse(
            "the Activity must not stop the service on destroy: $onDestroy",
            onDestroy.contains("stopService") || onDestroy.contains("ACTION_STOP"),
        )
    }

    @Test
    fun `explicit stop is still possible`() {
        assertTrue(
            "an explicit stop path must remain for the user",
            service.contains("ACTION_STOP"),
        )
    }
}