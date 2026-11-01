package dev.brahma.launcher

import java.text.Normalizer
import java.util.Locale

data class AppEntry(val id: String, val label: String, val packageName: String)

object AppCatalog {
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "").lowercase(Locale.ROOT)

    fun sorted(apps: List<AppEntry>) = apps.distinctBy { it.id }
        .sortedWith(compareBy<AppEntry> { normalized(it.label) }.thenBy { it.id })

    fun search(apps: List<AppEntry>, query: String): List<AppEntry> {
        val terms = normalized(query).trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return apps.filter { app -> terms.all { normalized(app.label).contains(it) } }
    }

    fun section(label: String): String = normalized(label).firstOrNull()?.uppercaseChar()
        ?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

    fun favorites(apps: List<AppEntry>, ids: List<String>) = ids.mapNotNull { id -> apps.find { it.id == id } }

    /** Drawer rows: favorites first, then every app under its A–Z (or #) header. Search results are flat. */
    fun drawerRows(apps: List<AppEntry>, favoriteIds: Set<String>, query: String): List<DrawerRow> {
        if (query.isNotBlank()) return search(apps, query).map { DrawerRow.App(it) }
        val rows = mutableListOf<DrawerRow>()
        val favorites = apps.filter { it.id in favoriteIds }
        if (favorites.isNotEmpty()) {
            rows += DrawerRow.Header(FAVORITES)
            favorites.forEach { rows += DrawerRow.App(it) }
        }
        apps.groupBy { section(it.label) }.toSortedMap(compareBy { if (it == "#") "~" else it }).forEach { (letter, group) ->
            rows += DrawerRow.Header(letter)
            group.forEach { rows += DrawerRow.App(it) }
        }
        return rows
    }

    const val FAVORITES = "★"
}

sealed class DrawerRow {
    data class Header(val title: String) : DrawerRow()
    data class App(val app: AppEntry) : DrawerRow()
}

data class SuiteApp(val name: String, val purpose: String, val packageName: String, val repository: String, val glyph: String)

val suiteApps = listOf(
    SuiteApp("Brahma", "Your life, together", "dev.brahma.app", "brahma", "B"),
    SuiteApp("Artha", "Money & clarity", "dev.artha.artha", "artha", "₹"),
    SuiteApp("Vidya", "A little more every day", "dev.brahma.vidya", "vidya", "V"),
    SuiteApp("Vani", "Your voice, connected", "dev.brahma.vani", "vani", "◌"),
    SuiteApp("Anveshana", "Find your next home", "dev.brahma.anveshana", "anveshana", "⌂"),
    SuiteApp("Udyoga", "Make your next move", "dev.brahma.udyoga", "udyoga", "U"),
    SuiteApp("Bhojana", "Everyday essentials", "dev.brahma.bhojana", "bhojana", "◒"),
    SuiteApp("Paattu", "Room for music", "dev.paattu.paattu", "paattu", "♪"),
    SuiteApp("Chithram", "Stories on screen", "dev.chithram.chithram", "chithram", "▷")
)
