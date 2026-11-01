package dev.brahma.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class EffectType(val title: String) {
    OUTLINE("Outline"), GLOW("Neon glow"), SHADOW("Shadow"), DUOTONE("Duotone"), MONO("Mono"), POP("Pop colour"), FADE("Fade")
}

/** One stackable effect. [size] and [strength] run from 0 to 1; not every effect uses both. */
data class StickerEffect(val type: EffectType, val color: Int, val size: Float = 0.4f, val strength: Float = 0.7f)

/** How a sticker moves on home. TILT shifts the subject against the phone's tilt, like a depth photo. */
enum class StickerMotion(val title: String) {
    NONE("Still"), FLOAT("Float"), SWAY("Sway"), BREATHE("Breathe"), WANDER("Wander"), TILT("Tilt with phone")
}

/** Where a sticker sits on one home layout: centre x and y, and width, as fractions of the screen. */
data class Place(val x: Float, val y: Float, val scale: Float)

/**
 * A sticker kept in the app's library. [subject] is false when only the plain picture is used
 * (no subject found, or a picture from before subject stickers). [x], [y] and [scale] are the
 * last place it was put; [places] keeps a place per home layout, so changing layout does not
 * drop the sticker onto that layout's text.
 */
data class Sticker(
    val id: String,
    val enabled: Boolean = true,
    val subject: Boolean = true,
    val softness: Float = 0.3f,
    val effects: List<StickerEffect> = emptyList(),
    val x: Float = 0.7f,
    val y: Float = 0.3f,
    val scale: Float = 0.45f,
    /** Changes whenever the picture, cutout or effects change, so cached drawings can be redrawn. */
    val version: Long = 0,
    val motion: StickerMotion = StickerMotion.NONE,
    val places: Map<String, Place> = emptyMap()
) {
    fun place(layout: String) = places[layout] ?: Place(x, y, scale)

    /** Puts the sticker at [place] on [layout]; layouts without their own place start from here too. */
    fun placed(layout: String, place: Place) = copy(x = place.x, y = place.y, scale = place.scale, places = places + (layout to place))

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        /** A clean sticker look to start from: white border and a soft shadow. */
        val startingEffects = listOf(
            StickerEffect(EffectType.OUTLINE, 0xFFFFFFFF.toInt(), size = 0.35f),
            StickerEffect(EffectType.SHADOW, 0xFF000000.toInt(), size = 0.3f, strength = 0.45f)
        )
    }
}

object StickerJson {
    fun encode(stickers: List<Sticker>): String = JSONArray().apply {
        stickers.forEach { sticker ->
            put(JSONObject().apply {
                put("id", sticker.id)
                put("enabled", sticker.enabled)
                put("subject", sticker.subject)
                put("softness", sticker.softness.toDouble())
                put("x", sticker.x.toDouble())
                put("y", sticker.y.toDouble())
                put("scale", sticker.scale.toDouble())
                put("version", sticker.version)
                put("motion", sticker.motion.name)
                put("places", JSONObject().apply {
                    sticker.places.forEach { (layout, place) ->
                        put(layout, JSONObject().apply {
                            put("x", place.x.toDouble())
                            put("y", place.y.toDouble())
                            put("scale", place.scale.toDouble())
                        })
                    }
                })
                put("effects", JSONArray().apply {
                    sticker.effects.forEach { effect ->
                        put(JSONObject().apply {
                            put("type", effect.type.name)
                            put("color", effect.color)
                            put("size", effect.size.toDouble())
                            put("strength", effect.strength.toDouble())
                        })
                    }
                })
            })
        }
    }.toString()

    /** Unknown effect names are skipped, so stickers survive future effect changes. */
    fun decode(text: String?): List<Sticker> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val effects = item.optJSONArray("effects") ?: JSONArray()
                val places = item.optJSONObject("places") ?: JSONObject()
                Sticker(
                    id = item.getString("id"),
                    enabled = item.optBoolean("enabled", true),
                    subject = item.optBoolean("subject", true),
                    softness = item.optDouble("softness", 0.3).toFloat(),
                    effects = (0 until effects.length()).mapNotNull { e ->
                        val effect = effects.getJSONObject(e)
                        EffectType.entries.find { it.name == effect.optString("type") }?.let { type ->
                            StickerEffect(type, effect.optInt("color", -1), effect.optDouble("size", 0.4).toFloat(),
                                effect.optDouble("strength", 0.7).toFloat())
                        }
                    },
                    x = item.optDouble("x", 0.7).toFloat(),
                    y = item.optDouble("y", 0.3).toFloat(),
                    scale = item.optDouble("scale", 0.45).toFloat(),
                    version = item.optLong("version", 0),
                    motion = StickerMotion.entries.find { it.name == item.optString("motion") } ?: StickerMotion.NONE,
                    places = places.keys().asSequence().mapNotNull { layout ->
                        places.optJSONObject(layout)?.let { place ->
                            layout to Place(place.optDouble("x", 0.7).toFloat(), place.optDouble("y", 0.3).toFloat(),
                                place.optDouble("scale", 0.45).toFloat())
                        }
                    }.toMap()
                )
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }
}

/** Picture files for each sticker, in app-private storage: source photo, cutout mask, drawn result. */
class StickerFiles(context: Context) {
    private val root = File(context.filesDir, "stickers")

    fun source(id: String) = File(dir(id), "source.png")
    fun mask(id: String) = File(dir(id), "mask.png")
    fun drawn(id: String) = File(dir(id), "drawn.png")
    /** ML Kit's untouched cutout, for "Reset" after refining. */
    fun auto(id: String) = File(dir(id), "auto.png")

    fun save(file: File, bitmap: Bitmap) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!temp.renameTo(file)) temp.delete()
    }

    fun load(file: File, maxSide: Int = 0): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
            inMutable = true
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    private fun dir(id: String) = File(root, id).apply { mkdirs() }

    companion object {
        const val SOURCE_SIDE = 1600

        fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
            if (maxSide <= 0) return 1
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
            return sample
        }

        /** Reads a picked picture upright, sampled down to about [SOURCE_SIDE] pixels on its long side. */
        fun decode(context: Context, uri: Uri): Bitmap? = try {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder applies the photo's EXIF rotation; BitmapFactory does not.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height, SOURCE_SIDE))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else decodeSampled(context, uri)
        } catch (_: Exception) {
            null
        }

        private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, SOURCE_SIDE)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }
}
