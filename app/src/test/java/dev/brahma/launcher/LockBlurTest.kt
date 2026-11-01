package dev.brahma.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockBlurTest {
    private fun flat(value: Float, w: Int = 20, h: Int = 14) =
        LinearImage(w, h, FloatArray(w * h) { value }, FloatArray(w * h) { value }, FloatArray(w * h) { value })

    private fun dot(w: Int = 31, h: Int = 31) = flat(0f, w, h).also { it.r[(h / 2) * w + w / 2] = 1f }

    @Test
    fun blursKeepAFlatPictureFlat() {
        listOf<(LinearImage) -> Unit>({ it.gaussian(5f) }, { it.disc(4.5f) }, { it.streak(9f) }, { it.zoom(10f, 7f, 0.2f) }).forEach { blur ->
            val image = flat(0.4f)
            blur(image)
            image.r.forEach { assertEquals(0.4f, it, 1e-4f) }
        }
    }

    @Test
    fun blursKeepTheLight() {
        val image = dot()
        image.gaussian(4f)
        assertEquals(1f, image.r.sum(), 1e-3f)
        val disc = dot()
        disc.disc(5f)
        assertEquals(1f, disc.r.sum(), 1e-3f)
    }

    @Test
    fun discIsFlatInsideAndEmptyOutside() {
        val image = dot()
        image.disc(5f)
        val w = image.width
        val c = 15 * w + 15
        assertEquals(image.r[c], image.r[c + 3], 1e-6f)
        assertEquals(0f, image.r[c + 7], 1e-6f)
    }

    @Test
    fun threeBoxesMatchTheGaussianWidth() {
        val sizes = LinearImage.boxes(4f)
        assertEquals(3, sizes.size)
        // The sum of box variances, (n² - 1) / 12, should be close to sigma².
        val variance = sizes.sumOf { (it * it - 1) / 12.0 }
        assertTrue(kotlin.math.abs(variance - 16) < 2.5)
    }

    @Test
    fun settingsRoundTrip() {
        val blur = LockBlur(BlurStyle.BOKEH, 0.3f, 0.8f, 0.2f, 0.1f, 0.9f, 0.4f)
        assertEquals(blur, LockBlur.decode(org.json.JSONObject(blur.encode().toString())))
        assertEquals(LockBlur(), LockBlur.decode(null))
    }
}
