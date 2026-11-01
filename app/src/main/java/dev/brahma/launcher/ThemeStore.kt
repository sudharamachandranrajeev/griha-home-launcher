package dev.brahma.launcher

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** One theme in the catalogue's index.json. */
data class CatalogueTheme(
    val id: String,
    val name: String,
    val version: Int,
    val engine: Int,
    val description: String,
    val requires: Set<String>,
    val previewUrl: String?,
    val packageUrl: String?,
    val packageSha256: String?,
    val packageSize: Long,
    val status: String
) {
    /** Features this Griha lacks; empty when it can draw the theme. */
    fun missing() = (requires - ThemeEngine.FEATURES) + (if (engine > ThemeEngine.VERSION) setOf("engine $engine") else emptySet())
}

/**
 * Downloaded themes, each in files/themes/<id>/. Nothing is fetched until the user opens the
 * catalogue. A theme is accepted only when its SHA-256 matches the catalogue, it unpacks within
 * the size limits, every path stays inside its own folder, and Griha can draw everything it
 * requires.
 */
class ThemeStore(private val root: File) {
    constructor(context: android.content.Context) : this(File(context.filesDir, "themes"))

    fun installed(): List<ThemeSpec> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it.name) }?.sortedBy { it.name } ?: emptyList()

    fun load(id: String): ThemeSpec? {
        if (!ID.matches(id)) return null
        val folder = File(root, id)
        val file = File(folder, "theme.json").takeIf { it.isFile } ?: return null
        return try { ThemeSpec.parse(file.readText(), folder) } catch (_: Exception) { null }
    }

    fun remove(id: String) {
        if (ID.matches(id)) File(root, id).deleteRecursively()
    }

    fun size(id: String): Long = File(root, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Unpacks a downloaded package after checking its checksum, replacing any older copy of the same theme. */
    fun install(entry: CatalogueTheme, archive: File): ThemeSpec {
        val expected = entry.packageSha256 ?: throw IOException("The catalogue lists no checksum for this theme")
        if (sha256(archive) != expected) throw IOException("The download doesn't match its checksum")
        if (!ID.matches(entry.id)) throw IOException("Bad theme id")
        root.mkdirs()
        val staging = File(root, ".${entry.id}.new").apply { deleteRecursively(); mkdirs() }
        try {
            archive.inputStream().use { unpack(it, staging) }
            val spec = ThemeSpec.parse(File(staging, "theme.json").readText(), staging)
            if (spec.id != entry.id) throw IOException("The package holds a different theme")
            if (spec.missing().isNotEmpty()) throw IOException("Needs a newer Griha: ${spec.missing().joinToString()}")
            val target = File(root, entry.id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Couldn't store the theme")
            return load(entry.id) ?: throw IOException("Couldn't read the stored theme")
        } finally {
            staging.deleteRecursively()
        }
    }

    companion object {
        const val INDEX_URL = "https://github.com/sudharamachandranrajeev/griha-themes/releases/latest/download/index.json"
        private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        /** Only these files may be in a theme package. */
        private val ALLOWED = Regex("theme\\.json|preview\\.png|elements\\.png|wallpapers/[a-z0-9-]+\\.(jpg|png|webp)|fonts/[a-z0-9-]+\\.(ttf|otf)")
        const val MAX_FILE = 8L * 1024 * 1024
        const val MAX_TOTAL = 20L * 1024 * 1024
        private const val TIMEOUT = 15_000

        fun parseIndex(json: String): List<CatalogueTheme> {
            val themes = JSONObject(json).optJSONArray("themes") ?: return emptyList()
            return (0 until themes.length()).map { themes.getJSONObject(it) }.map { t ->
                val pack = t.optJSONObject("package")
                val requires = t.optJSONArray("requires")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                CatalogueTheme(t.getString("id"), t.optString("name", t.getString("id")), t.optInt("version", 1), t.optInt("engine", 1),
                    t.optString("description"), requires, t.optString("preview").takeIf { it.startsWith("https://") },
                    pack?.optString("url")?.takeIf { it.startsWith("https://") }, pack?.optString("sha256")?.lowercase(),
                    pack?.optLong("size") ?: 0L, t.optString("status"))
            }
        }

        /**
         * Unpacks a theme ZIP into [target]. Java's ZIP reader does not stop "../" names (Zip Slip), so
         * each entry must be an allowed name and its canonical path must stay inside [target].
         */
        fun unpack(input: InputStream, target: File) {
            val base = target.canonicalFile
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!ALLOWED.matches(name)) throw IOException("Unexpected file in theme: $name")
                    val out = File(base, name).canonicalFile
                    if (!out.path.startsWith(base.path + File.separator)) throw IOException("Unsafe path in theme: $name")
                    out.parentFile?.mkdirs()
                    var written = 0L
                    out.outputStream().use { sink ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            total += read
                            if (written > MAX_FILE || total > MAX_TOTAL) throw IOException("Theme is too large")
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!File(base, "theme.json").isFile) throw IOException("No theme.json in the package")
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Downloads [url] to [file]; runs off the main thread. */
        fun download(url: String, file: File, limit: Long = MAX_TOTAL) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("User-Agent", "Griha-launcher")
            try {
                if (connection.responseCode != 200) throw IOException("Download failed (${connection.responseCode})")
                var total = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > limit) throw IOException("Download is too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        fun fetchIndex(cache: File): List<CatalogueTheme> {
            val file = File(cache, "theme-index.json")
            download(INDEX_URL, file, 1024 * 1024)
            return parseIndex(file.readText())
        }
    }
}
