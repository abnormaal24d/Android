package com.gimica.mergeblast.service

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * OCR detector used only when the normal Merge Blast board parser cannot find a playable board.
 * This keeps gameplay OCR fast while still letting us distinguish a full-screen interstitial from
 * an animation/menu and find textual Close/Skip controls when an ad SDK exposes them visually.
 */
class AdScreenDetector {
    companion object {
        private val AD_CTA_MARKERS = listOf(
            "installeren", "install", "download", "get app", "get the app",
            "play now", "speel nu", "open app", "app store", "google play"
        )

        private val AD_CONTEXT_MARKERS = listOf(
            "advertentie", "advertisement", "sponsored", "gesponsord", "adchoices",
            "ad choices", "learn more", "meer informatie"
        )

        private val CLOSE_MARKERS = listOf(
            "sluiten", "close", "close ad", "overslaan", "skip", "skip ad", "dismiss",
            "nee bedankt", "no thanks", "×", "✕", "✖", "x"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(
        bitmap: Bitmap,
        onSuccess: (AdVisualResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            onSuccess(AdVisualResult(false, null, "", bitmap.width, bitmap.height))
            return
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                val normalizedText = text.text.lowercase().replace('\n', ' ')
                val hasCta = AD_CTA_MARKERS.any(normalizedText::contains)
                val hasAdContext = AD_CONTEXT_MARKERS.any(normalizedText::contains)

                // The caller invokes this detector only after the game board itself was not found.
                // A strong install/download/play CTA is therefore sufficient; contextual ad words
                // are a second route for interstitials that use a less explicit CTA.
                val isAd = hasCta || hasAdContext

                val closePoint = text.textBlocks
                    .asSequence()
                    .flatMap { it.lines.asSequence() }
                    .flatMap { it.elements.asSequence() }
                    .mapNotNull { element ->
                        val bounds = element.boundingBox ?: return@mapNotNull null
                        val label = element.text.trim().lowercase()
                        if (!isCloseLabel(label, bounds, bitmap.width, bitmap.height)) {
                            return@mapNotNull null
                        }
                        bounds
                    }
                    .sortedBy { it.centerY() }
                    .firstOrNull()
                    ?.let { Point(it.centerX(), it.centerY()) }

                onSuccess(
                    AdVisualResult(
                        isAd = isAd,
                        closePoint = closePoint,
                        recognizedText = normalizedText.take(600),
                        screenWidth = bitmap.width,
                        screenHeight = bitmap.height
                    )
                )
            }
            .addOnFailureListener(onFailure)
    }

    fun close() {
        recognizer.close()
    }

    private fun isCloseLabel(label: String, bounds: Rect, width: Int, height: Int): Boolean {
        if (label.isBlank()) return false
        val exact = CLOSE_MARKERS.any { marker -> label == marker || label.startsWith("$marker ") }
        if (!exact) return false

        // A bare x is accepted only in the upper screen region; this avoids mistaking ordinary
        // creative text for a close control.
        if (label == "x") {
            return bounds.centerY() < height * 0.30f &&
                (bounds.centerX() < width * 0.30f || bounds.centerX() > width * 0.70f)
        }
        return true
    }
}

data class AdVisualResult(
    val isAd: Boolean,
    val closePoint: Point?,
    val recognizedText: String,
    val screenWidth: Int,
    val screenHeight: Int
)
