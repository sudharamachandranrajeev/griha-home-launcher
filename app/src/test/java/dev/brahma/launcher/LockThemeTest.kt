package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockThemeTest {
    private fun theme(lock: String) = ThemeSpec.parse("""
        {"id":"t","name":"T","engine":2,"palette":{"light":{"background":"#F6F1EA","accent":"#C21E5F","ink":"#1B1420"}},
         "lock":$lock}
    """.trimIndent(), null)

    @Test
    fun lockDesignKeepsThemeChoices() {
        val design = LockDesign(custom = true, followTheme = false, subjectLook = SubjectLook.DUOTONE, subjectAmount = 0.6f, themeClock = true)
        val back = LockDesign.decode(design.encode())
        assertEquals(design, back)
    }

    @Test
    fun olderDesignsTakeTheThemesPick() {
        val back = LockDesign.decode("""{"custom":true}""")
        assertEquals(SubjectLook.THEME, back.subjectLook)
        assertTrue(back.followTheme)
    }

    @Test
    fun themeLockSectionIsRead() {
        val spec = theme("""{"subject":"halo","amount":0.4,"font":"heavy"}""")
        assertEquals(SubjectLook.HALO, spec.lockSubject)
        assertEquals(0.4f, spec.lockAmount, 0.001f)
        assertEquals(LockFont.HEAVY, spec.lockFont)
    }

    @Test
    fun themesCannotPickTheMetaLooks() {
        assertNull(theme("""{"subject":"theme"}""").lockSubject)
        assertNull(theme("""{"subject":"sparkles"}""").lockSubject)
    }

    @Test
    fun themesPickResolvesAndUserPickWins() {
        val spec = theme("""{"subject":"rim","amount":0.3}""")
        assertEquals(SubjectLook.RIM to 0.3f, SubjectStyler.resolve(LockDesign(), spec))
        assertEquals(SubjectLook.NONE to 0f, SubjectStyler.resolve(LockDesign(), null))
        assertEquals(SubjectLook.WASH to 0.8f, SubjectStyler.resolve(LockDesign(subjectLook = SubjectLook.WASH, subjectAmount = 0.8f), spec))
    }

    @Test
    fun lockTextIsShadedUntilItReads() {
        for (under in listOf(0xFF101010.toInt(), 0xFFF4F4F4.toInt(), 0xFF7A7A7A.toInt(), 0xFFC21E5F.toInt())) {
            val shade = LockScreen.readable(0xFFC21E5F.toInt(), under)
            assertTrue("on ${Integer.toHexString(under)}", ThemeSpec.contrast(shade, under) >= ThemeSpec.MIN_CONTRAST)
        }
    }

    @Test
    fun lockClockTakesABlockOrAList() {
        assertEquals("stats", theme("""{"clock":{"type":"stats"}}""").lockClock!!.getJSONObject(0).getString("type"))
        assertEquals(2, theme("""{"clock":[{"type":"stats"},{"type":"week"}]}""").lockClock!!.length())
        assertNull(theme("""{}""").lockClock)
    }
}
