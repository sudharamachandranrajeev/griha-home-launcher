package dev.brahma.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StickersTest {
    @Test fun stickerLibraryRoundTrips() {
        val stickers = listOf(
            Sticker("a", enabled = false, subject = true, softness = 0.5f, x = 0.2f, y = 0.8f, scale = 0.3f, version = 42,
                effects = listOf(StickerEffect(EffectType.GLOW, 0xFFFF3B8D.toInt(), 0.6f, 0.9f), StickerEffect(EffectType.FADE, 0, 0f, 0.2f))),
            Sticker("b", subject = false)
        )
        assertEquals(stickers, StickerJson.decode(StickerJson.encode(stickers)))
    }

    @Test fun motionAndPerLayoutPlacesRoundTrip() {
        val sticker = Sticker("a", motion = StickerMotion.TILT).placed("ARCHES", Place(0.8f, 0.2f, 0.3f))
        val back = StickerJson.decode(StickerJson.encode(listOf(sticker))).single()
        assertEquals(sticker, back)
        assertEquals(Place(0.8f, 0.2f, 0.3f), back.place("ARCHES"))
        // A layout with no place of its own starts from the last place.
        assertEquals(Place(0.8f, 0.2f, 0.3f), back.place("DIAL"))
        assertEquals(StickerMotion.NONE, StickerJson.decode("""[{"id":"a","motion":"SPIN"}]""").single().motion)
    }

    @Test fun motionStaysInRangeAndTiltFollowsPhone() {
        val pose = Pose()
        StickerMotion.entries.forEach { motion ->
            for (step in 0..200) {
                Moves.pose(motion, step * 0.1f, 1f, -1f, 0f, pose)
                assertTrue(motion.name, kotlin.math.abs(pose.dx) <= 0.1f && kotlin.math.abs(pose.dy) <= 0.1f)
                assertTrue(motion.name, kotlin.math.abs(pose.rotation) <= 6.01f && pose.scale in 0.9f..1.1f)
            }
        }
        Moves.pose(StickerMotion.NONE, 3f, 1f, 1f, 0f, pose)
        assertEquals(0f, pose.dx)
        Moves.pose(StickerMotion.TILT, 0f, 1f, 0f, 0f, pose)
        assertTrue(pose.dx < 0)
        Moves.pose(StickerMotion.TILT, 0f, -1f, 0f, 0f, pose)
        assertTrue(pose.dx > 0)
    }

    @Test fun brokenOrUnknownStickerDataIsSkipped() {
        assertEquals(emptyList<Sticker>(), StickerJson.decode("not json"))
        assertEquals(emptyList<Sticker>(), StickerJson.decode(null))
        val future = """[{"id":"a","effects":[{"type":"SPARKLE"},{"type":"MONO","color":-1,"size":0.1,"strength":1}]}]"""
        assertEquals(listOf(EffectType.MONO), StickerJson.decode(future).single().effects.map { it.type })
    }

    @Test fun lockDesignRoundTripsAndFallsBack() {
        val design = LockDesign(custom = true, background = LockBackground.BLURRED, stickerId = "a", depth = false,
            font = LockFont.SCRIPT, textColor = 0xFF22C55E.toInt(), textSize = 0.9f, shadow = true, textY = 0.7f,
            subjectX = 0.3f, subjectY = 0.4f, subjectScale = 0.5f)
        assertEquals(design, LockDesign.decode(design.encode()))
        assertEquals(LockDesign(), LockDesign.decode("{oops"))
        assertNull(LockDesign.decode(LockDesign().encode()).stickerId)
    }

    @Test fun lockPhotoLookAndBackdropRoundTrip() {
        val design = LockDesign(custom = true, background = LockBackground.GENERATED, photoId = "p", photoVersion = 9,
            look = PhotoLook().with(LookPreset.CINEMA).copy(grain = 0.7f, preset = LookPreset.CUSTOM),
            backdrop = BackdropStyle.ORBS, seed = 123456789012L, daily = true, clearClock = false)
        assertEquals(design, LockDesign.decode(design.encode()))
        assertNull(LockDesign.decode(LockDesign().encode()).backdrop)
        assertEquals(true, LockDesign.decode(LockDesign().encode()).clearClock)
        assertNotEquals(LockDesign(photoVersion = 1).encode(), LockDesign(photoVersion = 2).encode())
    }

    @Test fun plainLookNeedsNoDrawingButAutoDoes() {
        assertTrue(PhotoLook().plain)
        assertTrue(!PhotoLook().with(LookPreset.AUTO).plain)
        assertTrue(!PhotoLook().with(LookPreset.NOIR).plain)
    }

    @Test fun balanceBrightensDarkPhotosAndAddsContrastToFlatOnes() {
        val dark = IntArray(100) { 0xFF202020.toInt() }
        assertTrue(PhotoLook.balance(dark).exposure > 0.2f)
        val bright = IntArray(100) { 0xFFF0F0F0.toInt() }
        assertTrue(PhotoLook.balance(bright).exposure < 0f)
        assertTrue(PhotoLook.balance(IntArray(100) { 0xFF808080.toInt() }).contrast > 0.1f)
        assertEquals(PhotoLook(), PhotoLook.balance(IntArray(0)))
    }

    @Test fun paletteFindsVividColoursAndAlwaysFillsTheCount() {
        val red = 0xFFD03030.toInt()
        val blue = 0xFF2040C0.toInt()
        val pixels = IntArray(300) { if (it < 200) red else blue } + IntArray(500) { 0xFF000000.toInt() }
        val palette = Palettes.dominant(pixels, 4)
        assertEquals(4, palette.size)
        assertTrue(((palette[0] shr 16) and 0xFF) > 180 && (palette[0] and 0xFF) < 80)
        assertTrue((palette[1] and 0xFF) > 150)
        assertEquals(3, Palettes.dominant(IntArray(0), 3).size)
        assertEquals(Backdrops.seedFor(5, false, 100), Backdrops.seedFor(5, false, 200))
        assertNotEquals(Backdrops.seedFor(5, true, 100), Backdrops.seedFor(5, true, 101))
    }

    @Test fun iosDesignRoundTrips() {
        val design = LockDesign(custom = true, ios = true, big = LockBig.WEEKDAY, font = LockFont.HEAVY)
        assertEquals(design, LockDesign.decode(design.encode()))
    }

    @Test fun subjectTopLandsOnTargetAndPhotoCoversScreen() {
        // Portrait photo 1200x1600 on a 1000x2200 screen; subject top at 400 photo pixels.
        val box = intArrayOf(300, 400, 900, 1600)
        listOf(500f, 800f).forEach { target ->
            val (scale, x, y) = LockScreen.fitSubject(1200, 1600, box, 1000, 2200, target).toList()
            assertEquals(target, y + 400 * scale, 1f)
            assertTrue(x <= 0f && x + 1200 * scale >= 1000f)
            assertTrue(y <= 0f && y + 1600 * scale >= 2200f - 0.5f)
        }
        // Too low a target needs more than the 1.8x zoom limit: it gets as close as the limit allows.
        val (zoom, _, top) = LockScreen.fitSubject(1200, 1600, box, 1000, 2200, 1100f).toList()
        assertEquals(1.8f * 1.375f, zoom, 0.001f)
        assertEquals(990f, top + 400 * zoom, 1f)
        // Subject at the very top cannot come down without leaving a gap; the photo still covers.
        val (scale, _, y) = LockScreen.fitSubject(1200, 1600, intArrayOf(0, 0, 1200, 1600), 1000, 2200, 700f).toList()
        assertTrue(y <= 0f && y + 1600 * scale >= 2200f - 0.5f)
    }

    @Test fun lockKeyChangesWithDesignAndStickerVersion() {
        val settings = LauncherSettings()
        val day = LocalDate.of(2026, 10, 31)
        val design = LockDesign(custom = true, stickerId = "a")
        val base = LockScreen.key(settings, false, day, design, Sticker("a", version = 1))
        assertNotEquals(base, LockScreen.key(settings, false, day, design.copy(depth = false), Sticker("a", version = 1)))
        assertNotEquals(base, LockScreen.key(settings, false, day, design, Sticker("a", version = 2)))
        assertEquals(base, LockScreen.key(settings, false, day, design, Sticker("a", version = 1)))
    }

    @Test fun maskBoundsCoverVisiblePixelsOnly() {
        val alpha = IntArray(16).also {
            it[5] = 255 // (1,1)
            it[10] = 200 // (2,2)
            it[15] = 4 // (3,3), too faint to count
        }
        assertArrayEquals(intArrayOf(1, 1, 3, 3), Masks.bounds(alpha, 4, 8))
        assertNull(Masks.bounds(IntArray(16), 4, 8))
    }

    @Test fun confidenceBecomesAlphaOrNothing() {
        assertArrayEquals(intArrayOf(0, 127, 255), Masks.fromConfidence(floatArrayOf(0f, 0.5f, 1f), minimumShare = 0.3f))
        assertNull(Masks.fromConfidence(FloatArray(1000) { if (it == 0) 1f else 0f }))
    }

    @Test fun sampleSizeHalvesUntilUnderLimit() {
        assertEquals(1, StickerFiles.sampleFor(1000, 800, 1600))
        assertEquals(2, StickerFiles.sampleFor(4000, 3000, 1600))
        assertEquals(1, StickerFiles.sampleFor(4000, 3000, 0))
    }

    @Test fun iconPackComponentsAreNormalised() {
        assertEquals("a.b/a.b.Main", IconPacks.componentKey("ComponentInfo{a.b/a.b.Main}"))
        assertEquals("a.b/a.b.Main", IconPacks.componentKey("ComponentInfo{a.b/.Main}"))
        assertNull(IconPacks.componentKey("garbage"))
        assertNull(IconPacks.componentKey("ComponentInfo{a.b/}"))
    }

    @Test fun lockTextEffectRoundTripsAndUnknownFallsBack() {
        val design = LockDesign(custom = true, style = LockStyle.WITCHER)
        assertEquals(design, LockDesign.decode(design.encode()))
        assertEquals(LockStyle.PLAIN, LockDesign.decode("""{"fx":"SPARKLE"}""").style)
    }

    @Test fun wallpaperPackKeepsOnlyPicturesAndIgnoresEntryNames() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            fun put(name: String, data: ByteArray) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry() }
            put("../../evil.jpg", byteArrayOf(1, 2, 3))
            put("pack/notes.txt", byteArrayOf(4))
            put("__MACOSX/pack/._a.jpg", byteArrayOf(5))
            put("pack/.hidden.png", byteArrayOf(6))
            put("pack/b.WEBP", byteArrayOf(7, 8))
            put("pack/huge.png", ByteArray(WallpaperPack.MAX_IMAGE_BYTES + 1))
        }
        val zipped = bytes.toByteArray()
        assertTrue(WallpaperPack.isZip(zipped))
        assertTrue(!WallpaperPack.isZip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        val found = mutableListOf<ByteArray>()
        WallpaperPack.pictures(zipped.inputStream()) { found += it; true }
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(7, 8)), found.map { it.toList() })
        // Stopping early stops reading.
        var seen = 0
        WallpaperPack.pictures(zipped.inputStream()) { seen++; false }
        assertEquals(1, seen)
        assertNull(WallpaperPack.readCapped(ByteArray(10).inputStream(), 9))
        assertEquals(10, WallpaperPack.readCapped(ByteArray(10).inputStream(), 10)?.size)
    }

    @Test fun luxeDataRoundTripsAndBrokenDataFallsBack() {
        val data = LuxeData(tagline = "t", cards = setOf(LuxeCard.QUOTES), tasks = listOf(LuxeTask("a", "Run", 420, TaskKind.BODY)),
            done = mapOf("2026-11-01" to setOf("a")), focus = mapOf("2026-11-01" to 30), focusApps = setOf("x/y"),
            focusStart = 5, focusUntil = 10, steps = listOf(LuxeStep("s", "Step", true)), reminder = "Stretch", reminderMinute = 600,
            quoteTopics = setOf("Calm"), bookmarks = setOf(3), quoteShift = 2)
        assertEquals(data, LuxeData.decode(data.encode()))
        assertEquals(LuxeData(), LuxeData.decode("{nope"))
        assertEquals(LuxeData().cards, LuxeData.decode("{}").cards)
    }

    @Test fun focusHidesAppsOnlyWhileRunningAndCountsMinutes() {
        val zone = java.time.ZoneOffset.UTC
        val start = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val running = LuxeData(focusApps = setOf("a/b")).startFocus(start, 25)
        assertEquals(setOf("a/b"), running.hidden(start + 60_000))
        assertEquals(emptySet<String>(), running.hidden(start + 25 * 60_000))
        assertEquals(running, running.settle(start + 60_000, zone))
        val finished = running.settle(start + 40 * 60_000, zone)
        assertEquals(25, finished.focus["2026-11-01"])
        assertEquals(0L, finished.focusUntil)
        assertEquals(10, running.endFocus(start + 10 * 60_000, zone).focus["2026-11-01"])
    }

    @Test fun streakForgivesTwoMissesAWeekAndNeverDropsToZero() {
        assertEquals(Streaks.Result(0, 0, 2), Streaks.compute(emptySet(), 100))
        // Ten kept days in a row.
        assertEquals(10, Streaks.compute((0L..9L).toSet(), 9).current)
        // Today not yet kept does not count as a miss.
        assertEquals(10, Streaks.compute((0L..9L).toSet(), 10).current)
        // Two misses in a week are free.
        assertEquals(5, Streaks.compute(setOf(0L, 1L, 4L, 5L, 6L), 6).current)
        // A third miss halves the streak, rounding up so it stays above zero.
        val result = Streaks.compute(setOf(0L, 1L, 2L, 3L), 7)
        assertEquals(2, result.current)
        assertEquals(4, result.best)
        assertTrue(Streaks.compute(setOf(0L), 60).current >= 1)
    }

    @Test fun keptDaysNeedHalfTheScheduleOrAFocusSession() {
        val tasks = listOf(LuxeTask("a", "A"), LuxeTask("b", "B"), LuxeTask("c", "C"))
        val data = LuxeData(tasks = tasks, done = mapOf("2026-11-01" to setOf("a", "b"), "2026-11-02" to setOf("a")),
            focus = mapOf("2026-11-03" to 25, "2026-11-04" to 10))
        val kept = data.keptDays().map { LocalDate.ofEpochDay(it).toString() }.toSet()
        assertEquals(setOf("2026-11-01", "2026-11-03"), kept)
        assertTrue(data.level() >= 1)
        assertNull(Quotes.pick(emptySet(), 1, 0))
        assertEquals("Calm", Quotes.pick(setOf("Calm"), 12345, 7)?.second?.topic)
    }
}
