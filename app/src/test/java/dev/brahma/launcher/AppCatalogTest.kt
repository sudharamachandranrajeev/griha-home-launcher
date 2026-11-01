package dev.brahma.launcher

import org.junit.Assert.*
import org.junit.Test

class AppCatalogTest {
    private val apps = listOf(AppEntry("b", "Brahma", "b"), AppEntry("c", "Café Notes", "c"), AppEntry("a", "Artha", "a"))
    @Test fun stableSortingDeduplicatesComponents() {
        assertEquals(listOf("a", "b", "c"), AppCatalog.sorted(apps + apps[0]).map { it.id })
    }
    @Test fun searchHandlesAccentsWhitespaceAndMultipleWords() {
        assertEquals(listOf(apps[1]), AppCatalog.search(apps, "  CAFE  notes "))
        assertEquals(apps, AppCatalog.search(apps, "  "))
        assertTrue(AppCatalog.search(apps, "missing").isEmpty())
    }
    @Test fun favoritesPreserveOrderAndIgnoreRemovedApps() {
        assertEquals(listOf(apps[2], apps[0]), AppCatalog.favorites(apps, listOf("a", "gone", "b")))
    }
    @Test fun alphabetHandlesAccentsSymbolsAndEmptyLabels() {
        assertEquals("E", AppCatalog.section("École"))
        assertEquals("#", AppCatalog.section("123"))
        assertEquals("#", AppCatalog.section(""))
    }
}

class DrawerRowsTest {
    private val apps = AppCatalog.sorted(listOf(AppEntry("c", "Camera", "c"), AppEntry("a", "Artha", "a"),
        AppEntry("n", "123 Notes", "n"), AppEntry("b", "Brahma", "b"), AppEntry("a2", "Alarm", "a2")))

    @Test fun everyAppAppearsOnceUnderItsLetterWithFavoritesFirst() {
        val rows = AppCatalog.drawerRows(apps, setOf("c"), "")
        assertEquals(listOf("★", "c", "A", "a2", "a", "B", "b", "C", "c", "#", "n"), rows.map {
            when (it) { is DrawerRow.Header -> it.title; is DrawerRow.App -> it.app.id }
        })
    }

    @Test fun searchIsFlatWithoutHeaders() {
        assertEquals(listOf(DrawerRow.App(apps.first { it.id == "b" })), AppCatalog.drawerRows(apps, emptySet(), "brah"))
    }
}
