package dev.brahma.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** One published Griha version and its installer. */
data class Release(
    val tag: String,
    val version: String,
    val notes: String,
    val prerelease: Boolean,
    val published: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** SHA-256 of the installer in lower-case hex, from GitHub's digest or the release notes. */
    val sha256: String?
)

/**
 * Updates and rollbacks from the project's GitHub releases. Nothing is sent until you tap Check:
 * then Griha asks api.github.com for the release list, and downloads an installer only when you
 * pick one. Each download is checked against its published SHA-256 and against Griha's own
 * signing certificate before Android is asked to install it.
 */
object Updates {
    const val REPO = "sudharamachandranrajeev/griha"
    private const val TIMEOUT_MILLIS = 15_000
    private val HEX64 = Regex("\\b[0-9a-fA-F]{64}\\b")

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: PackageManager.NameNotFoundException) {
        "0"
    }

    /** Releases newest first; drafts are left out, and betas unless [betas]. */
    fun parse(json: String, betas: Boolean): List<Release> {
        val all = JSONArray(json)
        return (0 until all.length()).map { all.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && (betas || !it.optBoolean("prerelease")) }
            .map { release ->
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk") }
                val apk = apks.firstOrNull { it.optString("name").startsWith("griha-launcher") } ?: apks.firstOrNull()
                val tag = release.optString("tag_name")
                val notes = release.optString("body")
                val name = apk?.optString("name")
                Release(tag, versionOf(tag), notes, release.optBoolean("prerelease"), release.optString("published_at").take(10),
                    apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }, name,
                    apk?.optLong("size") ?: 0L, apk?.let { checksum(it, notes, name) })
            }
            .sortedWith { a, b -> compare(b.version, a.version) }
    }

    /** "v0.5.0" and "griha-0.5.0" both read as "0.5.0". */
    fun versionOf(tag: String): String {
        val start = tag.indexOfFirst { it.isDigit() }
        return if (start < 0) tag.trim() else tag.substring(start).trim()
    }

    /** Compares versions such as 0.4.0, 0.10.1 and 0.5.0-beta.2; a beta sorts before its release. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String): Pair<List<Int>, String> {
            val core = v.substringBefore('-').substringBefore('+')
            val pre = if ('-' in v) v.substringAfter('-').substringBefore('+') else ""
            return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (na, pa) = parts(a)
        val (nb, pb) = parts(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val diff = na.getOrElse(i) { 0 } - nb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return when {
            pa == pb -> 0
            pa.isEmpty() -> 1
            pb.isEmpty() -> -1
            else -> pa.compareTo(pb)
        }
    }

    /** GitHub's own asset digest first; otherwise a hash on the release-notes line that names the file, or the only hash there. */
    fun checksum(asset: JSONObject, notes: String, name: String?): String? {
        asset.optString("digest").takeIf { it.startsWith("sha256:") }?.let { return it.removePrefix("sha256:").lowercase(Locale.ROOT) }
        if (name != null) notes.lines().firstOrNull { name in it }?.let { line -> HEX64.find(line)?.let { return it.value.lowercase(Locale.ROOT) } }
        return HEX64.findAll(notes).map { it.value.lowercase(Locale.ROOT) }.distinct().singleOrNull()
    }

    /** Runs off the main thread. */
    fun fetch(betas: Boolean): List<Release> {
        val connection = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Griha-launcher")
        try {
            if (connection.responseCode != 200) throw java.io.IOException("GitHub answered ${connection.responseCode}")
            return parse(connection.inputStream.bufferedReader().use { it.readText() }, betas)
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [release]'s installer into the cache; [progress] gets 0 to 100. Runs off the main thread. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        val url = release.apkUrl ?: throw java.io.IOException("This version has no installer")
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val file = File(folder, "griha-${release.version}.apk")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Griha-launcher")
        try {
            if (connection.responseCode != 200) throw java.io.IOException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) { shown = percent; progress(percent) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return file
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

    /** True when [apk] is Griha signed with the same key as this copy; null when Android cannot tell. */
    @Suppress("DEPRECATION")
    fun sameSigner(context: Context, apk: File): Boolean? {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val mine = manager.getPackageInfo(context.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }?.map { it.toCharsString() }?.toSet()
        val theirs = certs(archive) ?: return null
        val ours = certs(mine) ?: return null
        return theirs.any { it in ours }
    }

    /** Hands [apk] to Android's package installer; [InstallResultReceiver] hears how it went. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("griha.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }
}

/** Asks for the user's go-ahead when Android needs it and reports how an install ended. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                // Only Android's own installer screen is opened from here.
                val installer = confirm.resolveActivity(context.packageManager)?.packageName
                if (installer == null || installer == context.packageName) return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Griha is up to date", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "Install cancelled", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(context, "Couldn't install: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"), Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Settings backup as JSON: every launcher and Luxe preference with its type. It lets settings
 * survive a rollback, which Android only allows after Griha is removed. Sticker and wallpaper
 * pictures are files, not settings, and are not included.
 */
object SettingsBackup {
    private val FILES = listOf("launcher", "luxe")

    fun export(context: Context): String = JSONObject().apply {
        put("app", "griha")
        put("format", 1)
        put("version", Updates.installedVersion(context))
        put("prefs", JSONObject().apply {
            FILES.forEach { name -> put(name, encode(context.getSharedPreferences(name, Context.MODE_PRIVATE))) }
        })
    }.toString(2)

    private fun encode(preferences: SharedPreferences) = JSONObject().apply {
        preferences.all.forEach { (key, value) ->
            val entry = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>()))
                else -> null
            }
            if (entry != null) put(key, entry)
        }
    }

    /** Replaces the launcher's settings with the backup's; false when [json] is not a Griha backup. */
    fun import(context: Context, json: String): Boolean {
        val root = try { JSONObject(json) } catch (_: org.json.JSONException) { return false }
        if (root.optString("app") != "griha") return false
        val prefs = root.optJSONObject("prefs") ?: return false
        FILES.forEach { name ->
            val saved = prefs.optJSONObject(name) ?: return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            saved.keys().forEach { key ->
                val entry = saved.optJSONObject(key) ?: return@forEach
                when (entry.optString("t")) {
                    "s" -> editor.putString(key, entry.optString("v"))
                    "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                    "i" -> editor.putInt(key, entry.optInt("v"))
                    "l" -> editor.putLong(key, entry.optLong("v"))
                    "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                    "set" -> entry.optJSONArray("v")?.let { array -> editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet()) }
                }
            }
            editor.commit()
        }
        return true
    }
}
