package dev.brahma.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.ZipInputStream

/** The wallpapers drawn for every theme. Each one uses only its layout's colours and shapes. */
enum class WallpaperKind(val title: String) { SIGNATURE("Signature"), GLOW("Glow"), ART("Abstract"), SILK("Abstract 2") }

/** A wallpaper the user can pick: one drawn for a theme, or one they imported. */
sealed class WallpaperSource {
    data class Theme(val layout: HomeLayout, val kind: WallpaperKind) : WallpaperSource()
    data class Imported(val file: File) : WallpaperSource()
}

object ThemeWallpapers {
    /** The same layout, colours and kind always draw the same picture. */
    fun render(context: Context, layout: HomeLayout, dark: Boolean, kind: WallpaperKind, width: Int, height: Int): Bitmap {
        val colors = layout.palette(dark)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            WallpaperKind.SIGNATURE -> {
                canvas.drawColor(colors.background)
                LockScreen.motif(context, canvas, layout, colors, width, height)
            }
            WallpaperKind.GLOW -> {
                canvas.drawPaint(Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, h, colors.background, mix(colors.background, colors.secondary, 0.35f), Shader.TileMode.CLAMP)
                })
                glow(canvas, w * 0.85f, h * 0.18f, w * 0.9f, colors.accent, 0x99)
                glow(canvas, w * 0.1f, h * 0.85f, w * 0.7f, colors.secondary, 0x77)
            }
            WallpaperKind.ART, WallpaperKind.SILK -> {
                val styles = BackdropStyle.entries
                val offset = if (kind == WallpaperKind.ART) 0 else 2
                val shade = if (colors.dark) Color.BLACK else Color.WHITE
                val palette = listOf(colors.accent, colors.secondary, mix(colors.accent, shade, 0.4f), mix(colors.secondary, colors.ink, 0.3f))
                Backdrops.draw(canvas, styles[(layout.ordinal + offset) % styles.size], palette, width, height, layout.ordinal * 7919L + offset)
                // A light veil of the background keeps icons and text readable on top.
                canvas.drawColor((colors.background and 0x00FFFFFF) or 0x38000000)
            }
        }
        return bitmap
    }

    private fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, colour: Int, alpha: Int) {
        canvas.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, radius, (colour and 0x00FFFFFF) or (alpha shl 24), colour and 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }
}

/**
 * Reads a wallpaper pack: a ZIP of pictures. Only the bytes of picture entries are handed on; entry names are
 * never used as paths, so a crafted name cannot write outside the app. Sizes are capped against ZIP bombs.
 */
object WallpaperPack {
    const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
    const val MAX_ENTRIES = 1000

    private val pictures = listOf(".jpg", ".jpeg", ".png", ".webp")

    /** A picture file, not a folder, hidden file or macOS resource fork. */
    fun isPicture(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty() || base.startsWith(".") || name.contains("__MACOSX")) return false
        return pictures.any { base.lowercase().endsWith(it) }
    }

    /** True when [head] starts like a ZIP file. */
    fun isZip(head: ByteArray) = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
        head[2] == 3.toByte() && head[3] == 4.toByte()

    /** Up to [limit] bytes of [input]; null when there are more. */
    fun readCapped(input: InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }

    /** Hands each picture in the ZIP to [onPicture] until it returns false or a limit is reached. */
    fun pictures(input: InputStream, onPicture: (ByteArray) -> Boolean) {
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return
                if (++entries > MAX_ENTRIES) return
                if (entry.isDirectory || !isPicture(entry.name)) continue
                val bytes = readCapped(zip, MAX_IMAGE_BYTES) ?: continue
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) return
                if (!onPicture(bytes)) return
            }
        }
    }
}

/** Imported wallpapers, stored as re-encoded JPEGs under generated names in the app's private files. */
class WallpaperFiles(private val context: Context) {
    private val root = File(context.filesDir, "wallpapers")

    class Result(val added: Int, val skipped: Int, val full: Boolean)

    fun list(): List<File> = root.listFiles { file -> file.isFile && NAME.matches(file.name) }?.sortedBy { it.lastModified() } ?: emptyList()

    /** Deletes only a file this class made. */
    fun delete(file: File) {
        if (file.parentFile?.canonicalPath == root.canonicalPath && NAME.matches(file.name)) file.delete()
    }

    /** Copies pictures and the pictures inside ZIP packs, each scaled to at most [maxSide] pixels. */
    fun import(uris: List<Uri>, maxSide: Int): Result {
        root.mkdirs()
        var added = 0
        var skipped = 0
        var count = list().size
        val keep: (ByteArray) -> Boolean = keep@{ bytes ->
            if (count >= MAX_COUNT) return@keep false
            if (store(bytes, maxSide)) {
                added++
                count++
            } else skipped++
            true
        }
        for (uri in uris) {
            if (count >= MAX_COUNT) break
            try {
                val head = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(4) }
                if (head == null) {
                    skipped++
                    continue
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    if (WallpaperPack.isZip(head)) WallpaperPack.pictures(input, keep)
                    else WallpaperPack.readCapped(input, WallpaperPack.MAX_IMAGE_BYTES)?.let { keep(it) } ?: skipped++
                }
            } catch (_: Exception) {
                skipped++
            }
        }
        return Result(added, skipped, count >= MAX_COUNT)
    }

    private fun store(bytes: ByteArray, maxSide: Int): Boolean {
        val decoded = decode(bytes, maxSide) ?: return false
        // Deep-colour pictures (16-bit PNG) decode to a wider format that JPEG cannot take; use plain 8-bit colour.
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() } ?: return false
        val file = File(root, UUID.randomUUID().toString().replace("-", "").take(20) + ".jpg")
        val temp = File(root, file.name + ".tmp")
        return try {
            // Re-encoding drops everything but the pixels, including EXIF location data.
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            temp.renameTo(file)
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
            bitmap.recycle()
        }
    }

    companion object {
        const val MAX_COUNT = 60
        private val NAME = Regex("[0-9a-f]{20}\\.jpg")

        fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
            // ImageDecoder turns the picture upright from its EXIF data; some rare files (16-bit PNG) it refuses, so BitmapFactory is the fallback.
            if (Build.VERSION.SDK_INT >= 28) {
                val upright = try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        decoder.setTargetSampleSize(StickerFiles.sampleFor(info.size.width, info.size.height, maxSide))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (_: Exception) {
                    null
                }
                if (upright != null) return upright
            }
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
                else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = StickerFiles.sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

        /** A saved wallpaper, sampled down to about [maxSide] pixels. */
        fun load(file: File, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = StickerFiles.sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
            })
        }
    }
}

// InputStream.readNBytes needs API 33; this reads up to [count] bytes on any version.
private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(out, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return out.copyOf(filled)
}
