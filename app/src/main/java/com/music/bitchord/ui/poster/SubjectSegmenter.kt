package com.music.bitchord.ui.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SubjectCutoutResult(
    val backgroundBitmap: Bitmap,
    val cutoutBitmap: Bitmap?,
    val coverage: Double,
    val method: String,
)

/**
 * Automatic on-device subject segmentation for Poster Lagu Studio:
 * 1) Primary: Google ML Kit Selfie Segmentation (bundled offline TFLite) with bilinear
 *    mask smoothing and balanced [0.25f .. 0.62f] feathering.
 * 2) Automatic fallback: Edge-contrast salient foreground extraction + 3x3 box alpha blur
 *    when the photo contains non-human subjects or ML Kit is unavailable.
 */
object SubjectSegmenter {

    suspend fun segmentUri(context: Context, uri: Uri): SubjectCutoutResult? = withContext(Dispatchers.Default) {
        val src = decodeScaledUri(context, uri, maxDim = 1280) ?: return@withContext null
        val cutout = segmentBitmapInternal(context.applicationContext, src)
        SubjectCutoutResult(
            backgroundBitmap = src,
            cutoutBitmap = cutout.first,
            coverage = cutout.second,
            method = cutout.third,
        )
    }

    fun segmentForeground(
        context: Context,
        imagePath: String,
        cacheDir: File,
    ): Map<String, Any?> {
        val file = File(imagePath)
        if (!file.exists()) {
            return mapOf("cutoutPath" to null, "coverage" to 0.0, "method" to "missing")
        }

        val src = decodeScaledFile(file, maxDim = 1280)
            ?: return mapOf("cutoutPath" to null, "coverage" to 0.0, "method" to "decode_error")

        try {
            val (cutout, coverage, method) = segmentBitmapInternal(context.applicationContext, src)
            if (cutout == null) {
                return mapOf("cutoutPath" to null, "coverage" to 0.0, "method" to "none")
            }
            val outPath = writeCutoutPng(cutout, cacheDir)
            cutout.recycle()
            return mapOf("cutoutPath" to outPath, "coverage" to coverage, "method" to method)
        } finally {
            src.recycle()
        }
    }

    private fun segmentBitmapInternal(
        appContext: Context,
        src: Bitmap,
    ): Triple<Bitmap?, Double, String> {
        runMlKitSelfie(appContext, src)?.let { return it }
        runBorderSaliencyFallback(src)?.let { return it }
        return Triple(null, 0.0, "none")
    }

    private fun decodeScaledUri(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null
            ensureArgb8888(raw)
        }.getOrNull()
    }

    private fun decodeScaledFile(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return ensureArgb8888(raw)
    }

    private fun ensureArgb8888(raw: Bitmap): Bitmap {
        if (raw.config == Bitmap.Config.ARGB_8888) return raw
        val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
        raw.recycle()
        return copy
    }

    private fun runMlKitSelfie(appContext: Context, src: Bitmap): Triple<Bitmap?, Double, String>? {
        var segmenter: Segmenter? = null
        return try {
            try {
                MlKitContext.initializeIfNeeded(appContext)
            } catch (_: Throwable) {
            }

            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
            segmenter = Segmentation.getClient(options)

            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)))
            val maskW = mask.width
            val maskH = mask.height
            if (maskW <= 0 || maskH <= 0) return null

            val buf: ByteBuffer = mask.buffer
            buf.rewind()
            val totalMask = maskW * maskH
            val conf = FloatArray(totalMask)
            for (i in 0 until totalMask) {
                conf[i] = if (buf.remaining() >= 4) buf.float else 0f
            }

            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val outPixels = IntArray(w * h)
            var fgCount = 0
            val scaleX = if (w > 1) (maskW - 1).toFloat() / (w - 1).toFloat() else 0f
            val scaleY = if (h > 1) (maskH - 1).toFloat() / (h - 1).toFloat() else 0f

            for (y in 0 until h) {
                val fy = y * scaleY
                val y0 = fy.toInt().coerceIn(0, maskH - 1)
                val y1 = min(y0 + 1, maskH - 1)
                val ty = fy - y0
                val row0 = y0 * maskW
                val row1 = y1 * maskW
                val dstRow = y * w
                for (x in 0 until w) {
                    val fx = x * scaleX
                    val x0 = fx.toInt().coerceIn(0, maskW - 1)
                    val x1 = min(x0 + 1, maskW - 1)
                    val tx = fx - x0
                    val c00 = conf[row0 + x0]
                    val c10 = conf[row0 + x1]
                    val c01 = conf[row1 + x0]
                    val c11 = conf[row1 + x1]
                    val cTop = c00 + (c10 - c00) * tx
                    val cBot = c01 + (c11 - c01) * tx
                    val c = cTop + (cBot - cTop) * ty

                    val alphaF = smoothstep(0.25f, 0.62f, c)
                    val a = (alphaF * 255f).toInt().coerceIn(0, 255)
                    if (a > 120) fgCount++
                    val px = pixels[dstRow + x]
                    outPixels[dstRow + x] = (a shl 24) or (px and 0x00FFFFFF)
                }
            }

            val coverage = fgCount.toDouble() / (w * h).toDouble()
            if (coverage < 0.015 || coverage > 0.97) return null

            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
            Triple(outBmp, coverage, "mlkit")
        } catch (_: Throwable) {
            null
        } finally {
            try {
                segmenter?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun runBorderSaliencyFallback(src: Bitmap): Triple<Bitmap?, Double, String>? {
        return try {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val samplesR = ArrayList<Int>(256)
            val samplesG = ArrayList<Int>(256)
            val samplesB = ArrayList<Int>(256)
            val stepX = max(1, w / 32)
            val stepY = max(1, h / 32)
            for (x in 0 until w step stepX) {
                val p = pixels[x]
                samplesR.add(Color.red(p))
                samplesG.add(Color.green(p))
                samplesB.add(Color.blue(p))
            }
            for (y in 0 until (h * 0.72).toInt() step stepY) {
                val pL = pixels[y * w]
                val pR = pixels[y * w + (w - 1)]
                samplesR.add(Color.red(pL)); samplesG.add(Color.green(pL)); samplesB.add(Color.blue(pL))
                samplesR.add(Color.red(pR)); samplesG.add(Color.green(pR)); samplesB.add(Color.blue(pR))
            }
            if (samplesR.isEmpty()) return null
            val bgR = samplesR.average()
            val bgG = samplesG.average()
            val bgB = samplesB.average()

            val rawAlpha = IntArray(w * h)
            val cx = w * 0.5f
            val cy = h * 0.56f

            for (y in 0 until h) {
                val dy = (y - cy) / (h * 0.52f)
                val dstRow = y * w
                for (x in 0 until w) {
                    val dx = (x - cx) / (w * 0.44f)
                    val radialDist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val centerPrior = 1.0f - smoothstep(0.55f, 1.35f, radialDist)
                    val px = pixels[dstRow + x]
                    val dr = Color.red(px) - bgR
                    val dg = Color.green(px) - bgG
                    val db = Color.blue(px) - bgB
                    val colorDist = (sqrt(dr * dr + dg * dg + db * db) / 255.0).toFloat()
                    val score = (colorDist * 1.45f) * (0.35f + 0.65f * centerPrior)
                    val alphaF = smoothstep(0.22f, 0.46f, score)
                    rawAlpha[dstRow + x] = (alphaF * 255f).toInt().coerceIn(0, 255)
                }
            }

            val outPixels = IntArray(w * h)
            var fgCount = 0
            for (y in 0 until h) {
                val yPrev = max(0, y - 1) * w
                val yCurr = y * w
                val yNext = min(h - 1, y + 1) * w
                for (x in 0 until w) {
                    val xPrev = max(0, x - 1)
                    val xNext = min(w - 1, x + 1)
                    val sum = rawAlpha[yPrev + xPrev] + rawAlpha[yPrev + x] + rawAlpha[yPrev + xNext] +
                        rawAlpha[yCurr + xPrev] + rawAlpha[yCurr + x] + rawAlpha[yCurr + xNext] +
                        rawAlpha[yNext + xPrev] + rawAlpha[yNext + x] + rawAlpha[yNext + xNext]
                    val a = (sum / 9).coerceIn(0, 255)
                    if (a > 120) fgCount++
                    val px = pixels[yCurr + x]
                    outPixels[yCurr + x] = (a shl 24) or (px and 0x00FFFFFF)
                }
            }

            val coverage = fgCount.toDouble() / (w * h).toDouble()
            if (coverage < 0.03 || coverage > 0.90) return null
            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
            Triple(outBmp, coverage, "saliency")
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCutoutPng(outBmp: Bitmap, cacheDir: File): String {
        val dir = File(cacheDir, "poster_media").apply { mkdirs() }
        val existing = dir.listFiles { f -> f.name.startsWith("cutout_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        for (old in existing.drop(6)) {
            runCatching { old.delete() }
        }
        val outFile = File(dir, "cutout_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { fos ->
            outBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        return outFile.absolutePath
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
