package com.dsh.mobile.runtime

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeAssetDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifestJson(baseUrl: String, sha: String, size: Long = 0) = """
        {
          "schema": 1,
          "baseUrl": "$baseUrl",
          "assets": [
            {
              "id": "proot",
              "path": "proot",
              "url": "",
              "sha256": "$sha",
              "sizeBytes": $size,
              "required": true,
              "installMode": "0755",
              "description": "test asset"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses manifest fields`() {
        val m = RuntimeAssetManifest.parse(
            manifestJson("https://cdn.example/x", "a".repeat(64), 1234),
        )
        assertEquals(1, m.schema)
        assertEquals("https://cdn.example/x", m.baseUrl)
        assertEquals(1, m.assets.size)
        val a = m.assets.first()
        assertEquals("proot", a.id)
        assertEquals(1234L, a.sizeBytes)
        assertEquals("0755", a.installMode)
        assertTrue(a.required)
        assertTrue(a.hasTrustedHash)
        assertEquals("https://cdn.example/x/proot", a.effectiveUrl(m.baseUrl))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects manifest with no assets`() {
        RuntimeAssetManifest.parse("""{"schema":1,"baseUrl":"x","assets":[]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate asset ids`() {
        RuntimeAssetManifest.parse(
            """
            {"assets":[
              {"id":"a","path":"a"},{"id":"a","path":"b"}
            ]}
            """.trimIndent(),
        )
    }

    @Test
    fun `effective url prefers per asset override`() {
        val m = RuntimeAssetManifest.parse(
            """
            {"baseUrl":"https://base/","assets":[
              {"id":"a","path":"a.txt","url":"https://other/a.txt"},
              {"id":"b","path":"b.txt"}
            ]}
            """.trimIndent(),
        )
        assertEquals("https://other/a.txt", m.assets[0].effectiveUrl(m.baseUrl))
        assertEquals("https://base/b.txt", m.assets[1].effectiveUrl(m.baseUrl))
    }

    @Test
    fun `blank manifest means not configured`() {
        val m = RuntimeAssetManifest.parse(
            """{"baseUrl":"","assets":[{"id":"node","path":"node.tar.xz"}]}""",
        )
        assertFalse(m.isConfigured)
        assertFalse(m.assets.first().hasTrustedHash)
    }

    @Test
    fun `sha256 matches known vector`() {
        // echo -n "abc" | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RuntimeAssets.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun `not configured is reported before any network call`() = runTest {
        val dir = tmp.newFolder("rt")
        val assets = RuntimeAssets(dir)
        val dl = RuntimeAssetDownloader(assets)
        val m = RuntimeAssetManifest.parse(
            """{"baseUrl":"","assets":[{"id":"node","path":"node.tar.xz"}]}""",
        )
        val state = dl.install(m)
        assertTrue(state is RuntimePayloadState.NotConfigured)
        assertEquals(1, (state as RuntimePayloadState.NotConfigured).missing.size)
    }

    @Test
    fun `refuses to install when no hash is recorded`() = runTest {
        val dir = tmp.newFolder("rt")
        val dl = RuntimeAssetDownloader(RuntimeAssets(dir))
        // Configured base URL but a blank sha256: must refuse, not trust.
        val m = RuntimeAssetManifest.parse(manifestJson("https://cdn.example/x", "", 10))
        val state = dl.install(m)
        assertTrue(state is RuntimePayloadState.Failed)
        assertTrue((state as RuntimePayloadState.Failed).message.contains("sha256"))
    }

    @Test
    fun `downloads and verifies a real asset over http`() {
        val payload = "#!/bin/sh\necho proot\n".toByteArray()
        val digest = RuntimeAssets.sha256(payload)

        TinyHttpServer(payload).use { server ->
            val dir = tmp.newFolder("rt")
            val assets = RuntimeAssets(dir)
            val state = kotlinx.coroutines.runBlocking {
                RuntimeAssetDownloader(assets).install(
                    RuntimeAssetManifest.parse(manifestJson(server.baseUrl, digest, payload.size.toLong())),
                )
            }
            assertEquals(RuntimePayloadState.Complete, state)
            val target = File(dir, "proot")
            assertTrue("asset must land on disk", target.exists())
            assertEquals(digest, RuntimeAssets.sha256(target))
            assertTrue("0755 assets must be executable", target.canExecute())
            assertFalse("no temp file may be left behind", File(dir, "proot.part").exists())
        }
    }

    @Test
    fun `rejects payload whose hash does not match`() {
        val payload = "malicious".toByteArray()
        val wrongDigest = RuntimeAssets.sha256("honest".toByteArray())

        TinyHttpServer(payload).use { server ->
            val dir = tmp.newFolder("rt")
            val state = kotlinx.coroutines.runBlocking {
                RuntimeAssetDownloader(RuntimeAssets(dir)).install(
                    RuntimeAssetManifest.parse(manifestJson(server.baseUrl, wrongDigest)),
                )
            }
            assertTrue(state is RuntimePayloadState.Failed)
            assertTrue(
                (state as RuntimePayloadState.Failed).message.contains("Checksum mismatch"),
            )
            assertFalse("tampered bytes must never be installed", File(dir, "proot").exists())
        }
    }

    @Test
    fun `http error surfaces as failure not crash`() {
        TinyHttpServer(ByteArray(0), statusLine = "404 Not Found").use { server ->
            val dir = tmp.newFolder("rt")
            val state = kotlinx.coroutines.runBlocking {
                RuntimeAssetDownloader(RuntimeAssets(dir)).install(
                    RuntimeAssetManifest.parse(manifestJson(server.baseUrl, "a".repeat(64))),
                )
            }
            assertTrue(state is RuntimePayloadState.Failed)
            assertTrue((state as RuntimePayloadState.Failed).message.contains("404"))
        }
    }

    @Test
    fun `already-installed asset is skipped without refetching`() {
        val payload = "real".toByteArray()
        val digest = RuntimeAssets.sha256(payload)
        TinyHttpServer(payload).use { server ->
            val dir = tmp.newFolder("rt")
            File(dir, "proot").writeBytes(payload)
            val state = kotlinx.coroutines.runBlocking {
                RuntimeAssetDownloader(RuntimeAssets(dir)).install(
                    RuntimeAssetManifest.parse(manifestJson(server.baseUrl, digest)),
                )
            }
            assertEquals(RuntimePayloadState.Complete, state)
            assertEquals("must not re-download a valid asset", 0, server.requestCount)
        }
    }

    @Test
    fun `isInstalled rejects a corrupted payload on re-evaluation`() {
        val dir = tmp.newFolder("rt")
        val assets = RuntimeAssets(dir)
        val payload = "real".toByteArray()
        val m = RuntimeAssetManifest.parse(manifestJson("https://x/", RuntimeAssets.sha256(payload)))
        File(dir, "proot").writeBytes(payload)

        assertEquals(RuntimePayloadState.Complete, assets.evaluate(m))

        // Corrupted after the fact: must no longer count as installed.
        File(dir, "proot").writeBytes("corrupt".toByteArray())
        val state = assets.evaluate(m)
        assertTrue(
            state is RuntimePayloadState.NotConfigured ||
                state is RuntimePayloadState.PartiallyPresent,
        )
    }
}