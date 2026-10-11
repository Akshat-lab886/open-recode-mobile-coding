package com.dsh.mobile.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `bootstrap.sh` and the Kotlin runtime must agree on where Node lives.
 *
 * VERIFIED on device: node ships as Alpine's own `nodejs` apk INSIDE
 * `rootfs.tar.gz`, so it lives at `<runtimeRoot>/usr/bin/node`. An earlier
 * revision of this script looked for `<runtimeRoot>/node/bin/node`, which can
 * never exist — the bootstrap would have reported success having installed
 * nothing. These tests pin both sides of that contract.
 */
class BootstrapPayloadContractTest {

    /** `$` must not be treated as a Kotlin template. */
    private val D = "$"

    private val script: String = run {
        val f = File("src/main/assets/bootstrap.sh").let {
            if (it.exists()) it else File("app/src/main/assets/bootstrap.sh")
        }
        f.readText()
    }

    /** Executable lines only — comments legitimately mention the old layout. */
    private val code: String = script.lineSequence()
        .filterNot { it.trimStart().startsWith("#") }
        .joinToString("\n")

    @Test
    fun `bootstrap expects node inside the rootfs`() {
        assertTrue(
            "bootstrap must look for the rootfs node path",
            script.contains("""NODE_BIN="${D}NODE_DIR/usr/bin/node""""),
        )
        assertTrue(
            "the done-marker check must use the rootfs node path",
            script.contains("""[ -x "${D}NODE_DIR/usr/bin/node" ]"""),
        )
    }

    @Test
    fun `bootstrap never expects a separate node directory`() {
        // The old `$RUNTIME_ROOT/node` layout is gone; reintroducing it would
        // silently pass a "bootstrapped" check that provisioned nothing.
        assertFalse(
            "NODE_DIR must default to the runtime root, not a node/ subdir",
            script.contains("""NODE_DIR="${D}{NODE_DIR:-${D}RUNTIME_ROOT/node}""""),
        )
        assertFalse(
            "no bare node/bin/node path should remain",
            code.contains("""${D}NODE_DIR/bin/node"""),
        )
    }

    @Test
    fun `bootstrap does not reinstall nodejs from the network`() {
        // nodejs/npm are baked into the shipped rootfs; re-adding them needs a
        // package index and network, and fails the install for no benefit.
        assertFalse(
            "nodejs should not be re-added via apk/apt",
            code.contains("ca-certificates nodejs npm"),
        )
    }

    @Test
    fun `kotlin constants match the rootfs layout`() {
        // These are the argv paths used when the child runs inside PRoot.
        assertTrue(
            "guest node must be /usr/bin/node",
            AgentRuntime.GUEST_NODE_PATH == "/usr/bin/node",
        )
        assertTrue(
            "guest agents dir must be /agents",
            AgentRuntime.GUEST_AGENTS_DIR == "/agents",
        )
    }

    @Test
    fun `bootstrap never writes to slash tmp`() {
        // Observed on-device: an Android app cannot create /tmp, so a
        // `2>/tmp/foo` redirect fails with EACCES and that failure is what the
        // `if !` guard sees - extraction is reported broken even when the
        // tarball is perfectly fine. Scratch files must stay app-private.
        assertFalse(
            "bootstrap must not redirect to /tmp",
            code.contains("/tmp/dsh-rootfs-err.log"),
        )
        assertFalse("bootstrap must not write to /tmp at all", code.contains("2>/tmp/"))
    }

    @Test
    fun `guest binaries are launched through proot never directly`() {
        // A guest binary is Alpine/musl; the host is Android/bionic. Running
        // $NODE_DIR/usr/bin/node straight from the host shell cannot work -
        // observed on-device as "usr/bin/npm: No such file or directory" for a
        // file that demonstrably exists.
        assertFalse(
            "node must not be executed directly from the host shell",
            code.contains("""NODE_VER="$("${D}NODE_BIN --version"""),
        )
        assertFalse(
            "npm must not be executed directly from the host shell",
            code.contains(""""${D}NPM_BIN" install -g"""),
        )
        assertTrue(
            "node version must be probed through proot",
            code.contains("run_in_rootfs /usr/bin/env"),
        )
    }

    @Test
    fun `toolchain completeness check covers Alpine bin and usr bin`() {
        // Alpine does not merge /usr into /bin: bash is /bin/bash while node
        // and git are in /usr/bin. Checking only /usr/bin reported the shipped
        // rootfs as incomplete and sent first boot back to apk, re-downloading
        // 283 MiB that was already bundled.
        val script = script
        assertTrue(
            "must check /bin/bash as well as /usr/bin",
            script.contains("bin/bash"),
        )
    }

    @Test
    fun `bootstrap supplies the environment ProcessBuilder withholds`() {
        // This script runs under `set -u`. ProcessBuilder gives no login shell,
        // so HOME/TMPDIR/PATH may be unset and the first reference aborts the
        // whole bootstrap with "HOME: parameter not set". `run-as` exports
        // HOME, so manual testing on-device never reproduced it - it only
        // failed inside the real app process.
        val script = script
        assertTrue(
            "must default HOME before use",
            script.contains("""${D}{HOME:=${D}RUNTIME_ROOT}"""),
        )
        assertTrue(
            "must default TMPDIR before use",
            script.contains("""${D}{TMPDIR:=/tmp}"""),
        )
        assertTrue("must export the defaults", script.contains("export HOME TMPDIR PATH"))
    }

    @Test
    fun `bootstrap runs with a working directory visible to the guest`() {
        // PRoot chdir()s to the process CWD *inside the guest* and hard-exits
        // (255) when it is absent:
        //   can't chdir("/data/.../files/./.") in the guest rootfs
        // filesDir does not exist under runtimeRoot, so the guest cannot see
        // it. The manager must launch the script with runtimeRoot as cwd.
        val mgr = File("src/main/java/com/dsh/mobile/bootstrap/BootstrapManager.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/dsh/mobile/bootstrap/BootstrapManager.kt") }
            .readText()
        assertTrue(
            "bootstrap must be launched with runtimeRoot as its working directory",
            mgr.contains(".directory(runtimeRoot)"),
        )
        assertFalse(
            "filesDir cwd is invisible to the guest and must not be used",
            mgr.contains(".directory(appContext.filesDir)"),
        )
    }

    @Test
    fun `every log reference is preceded by an assignment`() {
        // `set -u` turns a log line referencing an unset variable into a fatal
        // abort, and that cost a full bootstrap cycle to find. Only literal
        // `$NAME` references on log lines are checked; `$1`, `$(...)` and
        // `while read` loop variables are deliberately out of scope.
        val lines = script.lines()
        val assigned = mutableSetOf<String>()
        lines.forEach { line ->
            // Function parameters are bound before the body runs, so
            // `d="$2"` inside probe_exec() counts as an assignment for $d.
            // Several assignments can share one line, e.g. `src="$1"; d="$2"`.
            Regex("""\b(\w+)=""")
                .findAll(line).forEach { assigned.add(it.groupValues[1]) }
            Regex("""\bfor\s+(\w+)\s+in""").find(line)?.let { assigned.add(it.groupValues[1]) }
        }
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("log ")) return@forEachIndexed
            Regex("""\$([A-Za-z_]\w*)""").findAll(line).forEach { ref ->
                val name = ref.groupValues[1]
                assertTrue(
                    "line ${index + 1} logs \$$name before it is assigned: $line",
                    assigned.contains(name),
                )
            }
        }
    }

    @Test
    fun `script is valid posix shell`() {
        // `sh -n` is the cheapest structural check; a syntax error here would
        // surface on-device as a bootstrap failure with no usable log.
        val candidate = File("src/main/assets/bootstrap.sh").let {
            if (it.exists()) it else File("app/src/main/assets/bootstrap.sh")
        }
        val proc = ProcessBuilder("sh", "-n", candidate.absolutePath)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("sh -n reported: $out", proc.waitFor() == 0)
    }
}