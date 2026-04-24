package org.skepsun.kototoro.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drawable that plays an AVIS (animated AVIF) file by iterating frames through libavif.
 *
 * Android's native [android.graphics.ImageDecoder] does not produce an animated drawable for
 * AVIS at the time of writing — it decodes only the first frame. This fills that gap: we keep
 * the encoded bytes alive, spin up a libavif decoder, and post frames on a scheduling handler.
 */
class AnimatedAvifDrawable(
	@Suppress("unused") private val encoded: ByteBuffer,
	private var decoder: AvifDecoder?,
	private val frame: Bitmap,
	private val frameCount: Int,
	private val frameDurationsMs: LongArray,
	private val repetitionCount: Int,
) : Drawable(), Animatable, Runnable {

	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val intrinsicW = frame.width
	private val intrinsicH = frame.height

	private val handler = Handler(Looper.getMainLooper())
	private val running = AtomicBoolean(false)
	private var currentFrame = 0
	private var loopsDone = 0

	override fun draw(canvas: Canvas) {
		if (!frame.isRecycled) {
			canvas.drawBitmap(frame, null, bounds, paint)
		}
	}

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	override fun getIntrinsicWidth(): Int = intrinsicW
	override fun getIntrinsicHeight(): Int = intrinsicH

	override fun start() {
		if (frameCount <= 1) return
		if (decoder == null) return
		if (!running.compareAndSet(false, true)) return
		scheduleNextFrame()
	}

	override fun stop() {
		if (!running.compareAndSet(true, false)) return
		handler.removeCallbacks(this)
	}

	override fun isRunning(): Boolean = running.get()

	override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
		val changed = super.setVisible(visible, restart)
		if (visible) {
			if (restart || !running.get()) start()
		} else {
			stop()
		}
		return changed
	}

	override fun run() {
		if (!running.get()) return
		decodeExecutor.execute {
			if (!running.get()) return@execute
			val d = decoder ?: return@execute
			val next = currentFrame + 1
			if (next >= frameCount) {
				loopsDone++
				if (repetitionCount > 0 && loopsDone >= repetitionCount) {
					running.set(false)
					return@execute
				}
				currentFrame = 0
				d.nthFrame(0, frame)
			} else {
				currentFrame = next
				d.nextFrame(frame)
			}
			handler.post { invalidateSelf() }
			scheduleNextFrame()
		}
	}

	private fun scheduleNextFrame() {
		val delay = frameDurationsMs.getOrElse(currentFrame.coerceAtLeast(0)) { DEFAULT_FRAME_DELAY_MS }
			.coerceAtLeast(MIN_FRAME_DELAY_MS)
		handler.postAtTime(this, SystemClock.uptimeMillis() + delay)
	}

	fun dispose() {
		stop()
		decodeExecutor.execute {
			decoder?.release()
			decoder = null
		}
	}

	companion object {
		private const val MIN_FRAME_DELAY_MS = 20L
		private const val DEFAULT_FRAME_DELAY_MS = 100L

		private val decodeExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
			Thread(runnable, "avif-animator").apply {
				isDaemon = true
				priority = Thread.NORM_PRIORITY - 1
			}
		}
	}
}
