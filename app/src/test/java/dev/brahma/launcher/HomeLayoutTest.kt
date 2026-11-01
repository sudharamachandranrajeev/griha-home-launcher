package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    @Test fun missingOrUnknownPreferenceUsesDefault() {
        assertEquals(HomeLayout.MONO, HomeLayout.fromPreference(null))
        assertEquals(HomeLayout.MONO, HomeLayout.fromPreference("removed-layout"))
    }

    @Test fun everyLayoutRestoresFromItsSavedName() {
        HomeLayout.entries.forEach { layout ->
            assertEquals(layout, HomeLayout.fromPreference(layout.name))
        }
    }
}

class PaletteTest {
    @Test fun everyLayoutHasLightAndDarkPalettes() {
        HomeLayout.entries.forEach { layout ->
            org.junit.Assert.assertFalse(layout.palette(false).dark)
            org.junit.Assert.assertTrue(layout.palette(true).dark)
            org.junit.Assert.assertNotEquals(layout.palette(true).background, layout.palette(true).ink)
        }
    }
}
