package com.dsh.mobile.runtime

import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for bugs found by running the real stack on a physical
 * ARM64 Android device (Samsung SM-X115, Android 16).
 *
 * Each assertion below corresponds to an observed failure, not a hypothetical.
 */
class ProotWrapContractTest {

    /** `$` must not be treated as a Kotlin template. */
    private val D = "$"

    private val runtimeRootPath = "/data/data/com.dsh.mobile/files/dsh-runtime"
    private val workspacePath = "/data/data/com.dsh.mobile/files/workspaces/default"

    private fun args() = AgentRuntime.prootWrapperArgs(runtimeRootPath, workspacePath)

    @Test
    fun `binds proc dev and sys into the guest`() {
        val a = args()
        // Verified failure: without /dev the guest cannot open /dev/null and
        // git aborts immediately.
        assertTrue("/proc must be bound", a.contains("/proc"))
        assertTrue("/dev must be bound", a.contains("/dev"))
        assertTrue("/sys must be bound", a.contains("/sys"))
    }

    @Test
    fun `sets the runtime root and binds the workspace mount`() {
        val a = args()
        assertEquals(runtimeRootPath, a[a.indexOf("-r") + 1])
        assertTrue(
            "workspace must bind to /workspace",
            a.contains("$workspacePath:/workspace"),
        )
        assertEquals("/workspace", a[a.indexOf("-w") + 1])
    }

    @Test
    fun `enables link2symlink so the rootfs symlinks behave`() {
        assertTrue("--link2symlink required", args().contains("--link2symlink"))
    }

    @Test
    fun `guest path puts rootfs binaries ahead of android ones`() {
        val path = AgentRuntime.GUEST_PATH.split(":")
        // Verified failure: with the host Android PATH, guest `cat`, `ls` and
        // `uname` are all "not found", so any agent spawning git/rg/bash by
        // name fails.
        assertEquals("/usr/local/bin", path.first())
        assertTrue(path.indexOf("/usr/bin") < path.indexOf("/system/bin"))
        assertTrue(path.indexOf("/bin") < path.indexOf("/system/bin"))
        assertTrue(path.contains("/root/bin"))
    }

    @Test
    fun `guest dns is provided`() {
        val dns = AgentRuntime.RESOLV_CONF_CONTENT
        assertTrue(dns.contains("nameserver"))
        // A stock Alpine minirootfs ships without resolv.conf.
        assertTrue(dns.lines().size >= 1)
    }

    @Test
    fun `proot run overrides path and home but keeps key injection`() {
        val env = AgentRuntime.buildEnvironment(
            apiEnv = mapOf("DEEPSEEK_API_KEY" to "sk-test"),
            workspace = File(workspacePath),
            mode = AgentMode.PLAN,
            port = 3080,
            insideProot = true,
        )
        assertEquals(AgentRuntime.GUEST_PATH, env["PATH"])
        assertEquals("/root", env["HOME"])
        // Silent key injection must survive the proot env overrides.
        assertEquals("sk-test", env["DEEPSEEK_API_KEY"])
    }

    @Test
    fun `PROOT_TMP_DIR is set or proot cannot start at all`() {
        // VERIFIED on-device: without PROOT_TMP_DIR proot aborts with
        // "can't create temporary file: Permission denied" because it defaults
        // to /tmp, which an Android app cannot write. This was a total
        // showstopper for the whole runtime path, not a cosmetic issue.
        val env = AgentRuntime.buildEnvironment(
            apiEnv = emptyMap(),
            workspace = File(workspacePath),
            mode = AgentMode.PLAN,
            port = 3080,
            insideProot = true,
            prootTmpDir = "/data/data/com.dsh.mobile/files/dsh-runtime/tmp",
        )
        assertEquals(
            "/data/data/com.dsh.mobile/files/dsh-runtime/tmp",
            env["PROOT_TMP_DIR"],
        )
        assertTrue(
            "PROOT_TMP_DIR must not be /tmp: apps cannot write there",
            !env["PROOT_TMP_DIR"]!!.startsWith("/tmp"),
        )
    }

    @Test
    fun `PROOT_TMP_DIR is not forced outside proot`() {
        val env = AgentRuntime.buildEnvironment(
            apiEnv = emptyMap(),
            workspace = File(workspacePath),
            mode = AgentMode.READ_ONLY,
            port = 3080,
            insideProot = false,
        )
        assertFalse(env.containsKey("PROOT_TMP_DIR"))
    }

    @Test
    fun `bootstrap exports PROOT_TMP_DIR for its own proot calls`() {
        val script = File("src/main/assets/bootstrap.sh").let {
            if (it.exists()) it.readText() else File("app/src/main/assets/bootstrap.sh").readText()
        }
        assertTrue(
            "bootstrap must export PROOT_TMP_DIR",
            script.contains("export PROOT_TMP_DIR"),
        )
        assertTrue(
            "bootstrap must point PROOT_TMP_DIR somewhere app-writable",
            script.contains("""PROOT_TMP_DIR="${D}RUNTIME_ROOT/tmp""""),
        )
    }

    @Test
    fun `native run leaves the host path untouched`() {
        val env = AgentRuntime.buildEnvironment(
            apiEnv = emptyMap(),
            workspace = File(workspacePath),
            mode = AgentMode.READ_ONLY,
            port = 3080,
            insideProot = false,
        )
        assertFalse("PATH must not be forced outside proot", env.containsKey("PATH"))
        assertFalse(env.containsKey("HOME"))
    }

    @Test
    fun `manifest no longer asks for a standalone node archive`() {
        // node ships inside the rootfs as Alpine's nodejs apk. A separate
        // node-*-linux-arm64.tar.xz would be glibc and cannot run on musl.
        val json = File("src/main/assets/runtime-assets.json").let { f ->
            if (f.exists()) f.readText() else File("app/src/main/assets/runtime-assets.json").readText()
        }
        val manifest = RuntimeAssetManifest.parse(json)
        assertFalse(
            "manifest must not require a standalone node archive",
            manifest.assets.any { it.id == "node" || it.path.endsWith(".tar.xz") },
        )
        assertTrue(manifest.assets.any { it.id == "rootfs" })
        assertTrue(manifest.assets.any { it.id == "proot" })
    }

    @Test
    fun `manifest field names match what the parser reads`() {
        // Regression guard. An earlier revision of runtime-assets.json used
        // name/size/target/mode while RuntimeAssets.kt parses
        // id/sizeBytes/installMode. Every asset was silently dropped, the
        // manifest parsed empty, and the sizeBytes integrity check degraded to
        // "skip" because the value read as 0. Nothing failed until a device
        // refused to start, so these key names are pinned here.
        val json = File("src/main/assets/runtime-assets.json").let { f ->
            if (f.exists()) f.readText() else File("app/src/main/assets/runtime-assets.json").readText()
        }
        for (key in listOf("\"id\"", "\"url\"", "\"sha256\"", "\"sizeBytes\"", "\"installMode\"")) {
            assertTrue("manifest must declare $key", json.contains(key))
        }
        // The old names must be gone, otherwise someone will add them back
        // believing they are read.
        for (key in listOf("\"name\"", "\"size\"", "\"target\"", "\"mode\"")) {
            assertFalse("manifest must not use $key; the parser ignores it", json.contains(key))
        }
        val manifest = RuntimeAssetManifest.parse(json)
        assertTrue("assets parsed", manifest.assets.isNotEmpty())
        for (asset in manifest.assets) {
            assertTrue(
                "asset ${asset.id} has sizeBytes=0; integrity would be skipped",
                asset.sizeBytes > 0L,
            )
            assertEquals("asset ${asset.id} must be 64 hex chars", 64, asset.sha256.length)
        }
    }

    @Test
    fun `dsh entry point matches what npm actually installs`() {
        // VERIFIED on-device: npm lays the package out at
        // <agents>/lib/node_modules/@deepseek-ai/dsh/lib/bin.js and declares
        // "bin": {"dsh": "lib/bin.js"}. The previously hard-coded
        // "<agents>/dsh/bin/dsh.js" does not exist.
        assertEquals(
            "/agents/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
            AgentRuntime.dshEntryPoint("/agents"),
        )
    }

    @Test
    fun `dsh argv uses the real entry point not the npm shim`() {
        val argv = AgentRuntime.buildArgv(
            agent = ActiveAgent.DEEPSEEK_HARNESS,
            nodePath = "/usr/bin/node",
            agentsDir = "/agents",
            port = 3080,
        )
        assertTrue(
            "argv must invoke the real lib/bin.js through node",
            argv.any { it.endsWith("@deepseek-ai/dsh/lib/bin.js") },
        )
        assertTrue("must invoke node explicitly", argv.first().endsWith("/node"))
    }

    @Test
    fun `dsh argv remains the verified profile form`() {
        val argv = AgentRuntime.buildArgv(
            agent = ActiveAgent.DEEPSEEK_HARNESS,
            nodePath = "/root/usr/bin/node",
            agentsDir = "/root/agents",
            port = 3080,
        )
        assertTrue(argv.contains("--profile"))
        assertEquals("web", argv[argv.indexOf("--profile") + 1])
        assertTrue("--no-open required or the agent opens a browser", argv.contains("--no-open"))
        assertFalse("--workspace is rejected by the real CLI", argv.contains("--workspace"))
    }
}