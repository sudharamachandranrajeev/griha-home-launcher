package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemeStoreTest {
    private val theme = """
        {"id":"test-theme","name":"Test","version":2,"engine":1,"requires":["palette.v1","depth.outline"],
         "palette":{"light":{"background":"#F6F1EA","surface":"#FFFFFF","accent":"#C21E5F","secondary":"#D9246E","ink":"#1B1420","muted":"#6E6370"},
                    "dark":{"background":"#1B1418","surface":"#251C21","accent":"#F25C95","secondary":"#D9246E","ink":"#F8EEF2","muted":"#B4A4AC"}},
         "depth":{"corners":1.5,"outline":{"width":2,"colour":"ink","roles":["primary"]},
                  "card":{"fill":["#FFFFFF","#FBF6F0"],"shadows":[[0,10,24,"#5A2A3A@0.16"]]},
                  "primary":{"fill":["accent"],"glow":[[0,0,16,"accent@0.4"]]}},
         "backdrop":{"kind":"blob"},"touch":{"pulse":"ring"}}
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun temp() = Files.createTempDirectory("themes").toFile()

    @Test
    fun readsPaletteDepthAndBackdrop() {
        val spec = ThemeSpec.parse(theme)
        assertEquals("test-theme", spec.id)
        assertTrue(spec.missing().isEmpty())
        val light = spec.palette(false, UiStyle.SOFT)
        assertEquals(0xFFF6F1EA.toInt(), light.background)
        assertFalse(light.dark)
        assertTrue(spec.palette(true, UiStyle.SOFT).dark)
        assertEquals((0xC21E5F) or (0x66 shl 24), spec.colour("accent@0.4", light))
        assertEquals(0x80123456.toInt(), spec.colour("#80123456", light))
        val card = spec.depthSpec(light, 10f, Role.CARD, light.surface, false, 2f, null)
        assertEquals(15f, card.radius, 0.001f)
        assertEquals(1, card.outer.size)
        assertEquals(20f, card.outer[0].dy, 0.001f)
        val primary = spec.depthSpec(light, 10f, Role.PRIMARY, light.accent, false, 2f, null)
        assertTrue("a primary with its own glow always glows", primary.glowing)
        assertEquals(4f, primary.rimWidth, 0.001f)
        assertEquals(light.ink, primary.rimTop)
        assertEquals("ring", spec.pulse)
    }

    @Test
    fun refusesFeaturesThisGrihaLacks() {
        val spec = ThemeSpec.parse(theme.replace("\"depth.outline\"", "\"widget.ring\"").replace("\"engine\":1", "\"engine\":3"))
        assertEquals(setOf("widget.ring", "engine 3"), spec.missing())
    }

    @Test
    fun unpacksAllowedFiles() {
        val target = temp()
        ThemeStore.unpack(ByteArrayInputStream(zip("theme.json" to theme.toByteArray(), "wallpapers/home.jpg" to ByteArray(10))), target)
        assertTrue(File(target, "wallpapers/home.jpg").isFile)
    }

    @Test
    fun refusesPathsThatLeaveTheFolder() {
        listOf("../evil.json", "wallpapers/../../x.jpg", "/abs/theme.json", "classes.dex", "wallpapers/Home.JPG").forEach { name ->
            try {
                ThemeStore.unpack(ByteArrayInputStream(zip("theme.json" to theme.toByteArray(), name to ByteArray(4))), temp())
                fail("accepted $name")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun refusesOversizedFiles() {
        try {
            ThemeStore.unpack(ByteArrayInputStream(zip("theme.json" to theme.toByteArray(),
                "wallpapers/home.jpg" to ByteArray((ThemeStore.MAX_FILE + 1).toInt()))), temp())
            fail("accepted an oversized file")
        } catch (_: IOException) {
        }
    }

    @Test
    fun installChecksTheChecksum() {
        val root = temp()
        val archive = File(temp(), "t.zip").apply { writeBytes(zip("theme.json" to theme.toByteArray())) }
        val entry = CatalogueTheme("test-theme", "Test", 2, 1, "", emptySet(), null, "https://x", "0".repeat(64), 0, "")
        try { ThemeStore(root).install(entry, archive); fail("accepted a wrong checksum") } catch (_: IOException) {}
        val good = entry.copy(packageSha256 = ThemeStore.sha256(archive))
        val spec = ThemeStore(root).install(good, archive)
        assertEquals(2, spec.version)
        assertNotNull(ThemeStore(root).load("test-theme"))
        assertEquals(listOf("test-theme"), ThemeStore(root).installed().map { it.id })
    }

    @Test
    fun readsTheCatalogueIndex() {
        val index = """{"themes":[{"id":"a","name":"A","version":1,"engine":1,"requires":["palette.v1"],"status":"available",
            "preview":"https://e/a.png","package":{"url":"https://e/a.griha-theme","sha256":"ABC","size":10}},
            {"id":"b","engine":2,"requires":["widget.ring"],"status":"waiting"}]}"""
        val themes = ThemeStore.parseIndex(index)
        assertEquals("abc", themes[0].packageSha256)
        assertTrue(themes[0].missing().isEmpty())
        assertEquals(null, themes[1].packageUrl)
        assertFalse(themes[1].missing().isEmpty())
    }
}
