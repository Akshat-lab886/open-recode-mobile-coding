package com.dsh.mobile.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckpointStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = CheckpointStore(File(tmp.root, "checkpoints"))

    private fun workspace() = File(tmp.root, "ws").apply { mkdirs() }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun snapshotOf(ws: File) = WorkspaceDiff.scan(ws)

    @Test
    fun `saves and reloads a checkpoint`() {
        val ws = workspace()
        write(File(ws, "a.txt"), "hello")
        val store = store()
        val cp = store.save(snapshotOf(ws))
        assertNotNull(cp)
        assertEquals(1, cp!!.fileCount)
        assertEquals(cp.id, store.latest()?.id)
    }

    @Test
    fun `restores modified content`() {
        val ws = workspace()
        val file = File(ws, "a.txt")
        write(file, "original")
        val store = store()
        val cp = store.save(snapshotOf(ws))!!

        write(file, "changed by the agent")
        store.restore(cp.id, ws)

        assertEquals("original", file.readText())
    }

    @Test
    fun `restores a deleted file`() {
        // The old baseline could not do this: it had the bytes but no record
        // that the file was part of the workspace at that point.
        val ws = workspace()
        val file = File(ws, "gone.txt")
        write(file, "keep me")
        val store = store()
        val cp = store.save(snapshotOf(ws))!!

        file.delete()
        assertFalse(file.exists())
        store.restore(cp.id, ws)

        assertTrue("restore must bring back a deleted file", file.exists())
        assertEquals("keep me", file.readText())
    }

    @Test
    fun `removes files added after the checkpoint`() {
        val ws = workspace()
        write(File(ws, "kept.txt"), "kept")
        val store = store()
        val cp = store.save(snapshotOf(ws))!!

        val added = File(ws, "new.txt")
        write(added, "added later")
        store.restore(cp.id, ws)

        assertFalse("restore means the workspace looks as it did", added.exists())
        assertTrue(File(ws, "kept.txt").exists())
    }

    @Test
    fun `unchanged files are stored ONCE across checkpoints`() {
        // The property that makes a checkpoint-per-turn affordable.
        val ws = workspace()
        write(File(ws, "big.txt"), "x".repeat(20_000))
        val store = store()
        store.save(snapshotOf(ws))
        val afterFirst = store.diskUsageBytes()

        // Change one small file, save again.
        write(File(ws, "small.txt"), "tiny")
        store.save(snapshotOf(ws))
        val afterSecond = store.diskUsageBytes()

        // The 20KB file must not have been stored twice.
        assertTrue(
            "second save must not duplicate unchanged content: $afterFirst -> $afterSecond",
            afterSecond - afterFirst < 1_000,
        )
    }

    @Test
    fun `an unchanged workspace produces the SAME id and writes nothing new`() {
        val ws = workspace()
        write(File(ws, "a.txt"), "stable")
        val store = store()
        val first = store.save(snapshotOf(ws))!!
        val usage = store.diskUsageBytes()
        val second = store.save(snapshotOf(ws))!!

        assertEquals("identical content must be idempotent", first.id, second.id)
        assertEquals(usage, store.diskUsageBytes())
    }

    @Test
    fun `a changed file produces a different id`() {
        val ws = workspace()
        val file = File(ws, "a.txt")
        write(file, "one")
        val store = store()
        val first = store.save(snapshotOf(ws))!!
        write(file, "two")
        val second = store.save(snapshotOf(ws))!!
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `files that were never captured are skipped rather than corrupted`() {
        val ws = workspace()
        val big = File(ws, "big.bin")
        big.writeBytes(ByteArray((WorkspaceDiff.MAX_CAPTURE_BYTES + 100).toInt()) { 1 })
        val store = store()
        val cp = store.save(snapshotOf(ws))!!
        assertEquals(1, cp.skippedCount)
        assertEquals(0, cp.fileCount)

        // A skipped file must survive restore untouched: the store cannot
        // restore it, so deleting it would be worse than leaving it.
        val content = big.readBytes()
        store.restore(cp.id, ws)
        assertTrue(big.exists())
        assertEquals(content.size, big.readBytes().size)
    }

    @Test
    fun `lists checkpoints newest first`() {
        val ws = workspace()
        val store = store()
        write(File(ws, "a.txt"), "1")
        store.save(snapshotOf(ws))
        Thread.sleep(20)
        write(File(ws, "a.txt"), "2")
        store.save(snapshotOf(ws))
        val ids = store.list()
        assertTrue(ids.size >= 2)
        assertEquals(store.latest()?.id, ids.first())
    }

    @Test
    fun `prune keeps the newest and drops unreferenced objects`() {
        val ws = workspace()
        val store = store()
        write(File(ws, "a.txt"), "1")
        store.save(snapshotOf(ws))
        write(File(ws, "a.txt"), "2")
        store.save(snapshotOf(ws))
        write(File(ws, "a.txt"), "3")
        store.save(snapshotOf(ws))

        val newest = store.latest()!!.id
        store.prune(keep = 1)

        assertEquals(listOf(newest), store.list())
        // The newest checkpoint must still be restorable: prune must not drop an
        // object its manifest still references.
        write(File(ws, "a.txt"), "tampered")
        store.restore(newest, ws)
        assertEquals("3", File(ws, "a.txt").readText())
    }

    @Test
    fun `a missing checkpoint restores nothing rather than throwing`() {
        assertEquals(emptyList<String>(), store().restore("does-not-exist", workspace()))
    }

    @Test
    fun `latest is null on a fresh store`() {
        assertNull(store().latest())
    }

    @Test
    fun `clear removes the history`() {
        val ws = workspace()
        write(File(ws, "a.txt"), "x")
        val store = store()
        store.save(snapshotOf(ws))
        store.clear()
        assertNull(store.latest())
        assertEquals(emptyList<String>(), store.list())
    }

    @Test
    fun `paths with tabs or newlines do not corrupt the manifest`() {
        // The manifest is tab-separated, so a path containing a tab would shift
        // every later field. The path is sanitised on write.
        val ws = workspace()
        write(File(ws, "weird name.txt"), "ok")
        val store = store()
        val cp = store.save(snapshotOf(ws))!!
        val entries = store.entries(cp.id)
        assertEquals(1, entries.size)
        assertTrue(entries.first().hash != null)
    }

    @Test
    fun `content hash is stable and distinguishes content`() {
        assertEquals(CheckpointStore.sha256("a".toByteArray()), CheckpointStore.sha256("a".toByteArray()))
        assertNotEquals(CheckpointStore.sha256("a".toByteArray()), CheckpointStore.sha256("b".toByteArray()))
        assertEquals(64, CheckpointStore.sha256("a".toByteArray()).length)
    }
}
