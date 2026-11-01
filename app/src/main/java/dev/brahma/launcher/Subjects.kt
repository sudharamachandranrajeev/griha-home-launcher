package dev.brahma.launcher

import android.graphics.Bitmap
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions

/**
 * Finds the subject of a photo with Google ML Kit, on the device. Play services downloads the
 * model the first time; until then ML Kit reports it unavailable and the caller can retry.
 */
object Subjects {
    sealed class Result {
        class Found(val mask: Bitmap) : Result()
        object NoSubject : Result()
        object Downloading : Result()
        class Failed(val reason: String) : Result()
    }

    fun find(photo: Bitmap, onResult: (Result) -> Unit) {
        val segmenter = try {
            SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        } catch (error: Exception) {
            // No Play services, or a device ML Kit does not support.
            return onResult(Result.Failed(error.message ?: "Subject finder unavailable"))
        }
        segmenter.process(InputImage.fromBitmap(photo, 0))
            .addOnSuccessListener { result ->
                val buffer = result.foregroundConfidenceMask
                if (buffer == null) {
                    onResult(Result.NoSubject)
                } else {
                    val confidence = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    val alpha = Masks.fromConfidence(confidence)
                    onResult(if (alpha == null) Result.NoSubject else Result.Found(Bitmap.createBitmap(
                        IntArray(alpha.size) { (alpha[it] shl 24) or 0xFFFFFF }, photo.width, photo.height, Bitmap.Config.ARGB_8888
                    ).copy(Bitmap.Config.ARGB_8888, true)))
                }
                segmenter.close()
            }
            .addOnFailureListener { error ->
                onResult(if (error is MlKitException && error.errorCode == MlKitException.UNAVAILABLE) Result.Downloading
                    else Result.Failed(error.message ?: "Subject finder failed"))
                segmenter.close()
            }
    }
}
