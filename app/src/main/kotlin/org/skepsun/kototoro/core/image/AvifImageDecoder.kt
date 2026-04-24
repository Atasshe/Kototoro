package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import kotlinx.coroutines.runInterruptible
import org.aomedia.avif.android.AvifDecoder
import org.skepsun.kototoro.core.util.ext.readByteBuffer
import kotlin.math.roundToLong

class AvifImageDecoder(
	private val source: ImageSource,
	private val options: Options,
) : Decoder {

	override suspend fun decode(): DecodeResult = runInterruptible {
		val bytes = source.source().readByteBuffer()
		val decoder = AvifDecoder.create(bytes) ?: throw ImageDecodeException(
			uri = source.fileOrNull()?.toString(),
			format = "avif",
			message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
		)
		val frameCount = decoder.frameCount
		if (frameCount > 1) {
			val config = if (decoder.depth == 8 || decoder.alphaPresent) {
				Bitmap.Config.ARGB_8888
			} else {
				Bitmap.Config.RGB_565
			}
			val durations: DoubleArray? = decoder.frameDurations
			val durationsMs = LongArray(frameCount) { index ->
				val sec = durations?.getOrNull(index)
				if (sec != null) (sec * 1000.0).roundToLong() else 100L
			}
			val firstFrame = createBitmap(decoder.width, decoder.height, config)
			val firstResult = decoder.nextFrame(firstFrame)
			if (firstResult != 0) {
				firstFrame.recycle()
				decoder.release()
				throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = AvifDecoder.resultToString(firstResult),
				)
			}
			val drawable = AnimatedAvifDrawable(
				encoded = bytes,
				decoder = decoder,
				frame = firstFrame,
				frameCount = frameCount,
				frameDurationsMs = durationsMs,
				repetitionCount = decoder.repetitionCount,
			)
			return@runInterruptible DecodeResult(
				image = drawable.asImage(),
				isSampled = false,
			)
		}
		try {
			val config = if (decoder.depth == 8 || decoder.alphaPresent) {
				Bitmap.Config.ARGB_8888
			} else {
				Bitmap.Config.RGB_565
			}
			val bitmap = createBitmap(decoder.width, decoder.height, config)
			val result = decoder.nextFrame(bitmap)
			if (result != 0) {
				bitmap.recycle()
				throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = AvifDecoder.resultToString(result),
				)
			}
			val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
				srcWidth = bitmap.width,
				srcHeight = bitmap.height,
				targetSize = options.size,
				scale = options.scale,
				maxSize = options.maxBitmapSize,
			)
			if (dstWidth < bitmap.width || dstHeight < bitmap.height) {
				val scaled = bitmap.scale(dstWidth, dstHeight)
				bitmap.recycle()
				DecodeResult(
					image = scaled.asImage(),
					isSampled = true,
				)
			} else {
				DecodeResult(
					image = bitmap.asImage(),
					isSampled = false,
				)
			}
		} finally {
			decoder.release()
		}
	}

	class Factory : Decoder.Factory {

		override fun create(
			result: SourceFetchResult,
			options: Options,
			imageLoader: ImageLoader
		): Decoder? = if (isApplicable(result)) {
			AvifImageDecoder(result.source, options)
		} else {
			null
		}

		override fun equals(other: Any?) = other is Factory

		override fun hashCode() = javaClass.hashCode()

		private fun isApplicable(result: SourceFetchResult): Boolean {
			if (result.mimeType == "image/avif") return true
			return try {
				result.source.source().peek().use { peek ->
					if (!peek.request(12L)) return@use false
					val head = peek.readByteArray(12L)
					if (head[4] != 'f'.code.toByte() ||
						head[5] != 't'.code.toByte() ||
						head[6] != 'y'.code.toByte() ||
						head[7] != 'p'.code.toByte()
					) return@use false
					val major = String(head, 8, 4)
					if (major == "avif" || major == "avis") return@use true
					if (!peek.request(52L)) return@use false
					val tail = peek.readByteArray(52L)
					var i = 0
					while (i + 4 <= tail.size) {
						val brand = String(tail, i, 4)
						if (brand == "avif" || brand == "avis") return@use true
						i += 4
					}
					false
				}
			} catch (e: Exception) {
				false
			}
		}
	}
}
