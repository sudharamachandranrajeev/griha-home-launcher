package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val hash = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, notes: String = "", digest: String = "") = """
        {"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"published_at":"2026-10-01T10:00:00Z","body":${org.json.JSONObject.quote(notes)},
         "assets":[{"name":"griha-launcher-${tag.removePrefix("v")}.apk","size":10,"digest":"$digest",
                    "browser_download_url":"https://github.com/x/griha-launcher.apk"}]}
    """

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updates.compare("0.10.0", "0.9.9") > 0)
        assertTrue(Updates.compare("0.5.0", "0.5.0-beta.2") > 0)
        assertTrue(Updates.compare("0.5.0-beta.2", "0.5.0-beta.1") > 0)
        assertEquals(0, Updates.compare("1.0", "1.0.0"))
        assertEquals("0.5.0", Updates.versionOf("v0.5.0"))
    }

    @Test
    fun releasesAreSortedAndFiltered() {
        val json = "[${release("v0.4.0")},${release("v0.6.0-beta.1", prerelease = true)},${release("v0.5.0")},${release("v0.7.0", draft = true)}]"
        assertEquals(listOf("0.5.0", "0.4.0"), Updates.parse(json, betas = false).map { it.version })
        assertEquals(listOf("0.6.0-beta.1", "0.5.0", "0.4.0"), Updates.parse(json, betas = true).map { it.version })
    }

    @Test
    fun checksumPrefersDigestThenNamedLine() {
        assertEquals(hash, Updates.parse("[${release("v1.0.0", digest = "sha256:$hash", notes = other)}]", true).single().sha256)
        val notes = "SHA-256\n$other  something-else.zip\n$hash  griha-launcher-1.0.0.apk"
        assertEquals(hash, Updates.parse("[${release("v1.0.0", notes = notes)}]", true).single().sha256)
        assertNull(Updates.parse("[${release("v1.0.0", notes = "no checksum")}]", true).single().sha256)
    }
}
