package org.skepsun.kototoro.reader.ui.pager.vm

import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.IOException
import android.graphics.BitmapFactory
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.throttle
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.config.ReaderSettings

class PageViewModel(
	private val loader: PageLoader,
	private val enhancementController: ReaderPageEnhancementController,
	val settingsProducer: ReaderSettings.Producer,
	private val networkState: NetworkState,
	private val exceptionResolver: ExceptionResolver,
	private val isWebtoon: Boolean,
) : DefaultOnImageEventListener {

	data class LayerSources(
		val original: ImageSource,
		val translated: ImageSource?,
	)

	private val scope = loader.loaderScope + Dispatchers.Main.immediate
	private var job: Job? = null
	private var cachedBounds: Rect? = null
	private var boundPage: ContentPage? = null
	private var boundPageSplit: org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.NONE
	@Volatile
	private var pendingLayerSwitchPageId: Long? = null
	private val boundsCache = LinkedHashMap<String, Rect?>(64, 0.75f, true)

	init {
		enhancementController.observeTranslationUpdates()
			.onEach { pageId ->
				val page = boundPage ?: return@onEach
				if (page.id != pageId) return@onEach
				if (isLoading()) {
					pendingLayerSwitchPageId = pageId
					return@onEach
				}
				switchDisplayLayer(page)
			}.launchIn(scope)
	}

	val state = MutableStateFlow<PageState>(PageState.Empty)

	fun isLoading() = job?.isActive == true

	fun onBind(page: ContentPage, split: org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.NONE) {
		boundPage = page
		boundPageSplit = split
		pendingLayerSwitchPageId = null
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			doLoad(page, force = false)
		}
	}

	fun retry(page: ContentPage, isFromUser: Boolean) {
		val prevJob = job
		job = scope.launch {
			prevJob?.cancelAndJoin()
			val e = (state.value as? PageState.Error)?.error
			if (e != null && ExceptionResolver.canResolve(e)) {
				if (isFromUser) {
					exceptionResolver.resolve(e)
				}
			}
			withContext(Dispatchers.Default) {
				doLoad(page, force = true)
			}
		}
	}

	fun showErrorDetails(url: String?) {
		val e = (state.value as? PageState.Error)?.error ?: return
		exceptionResolver.showErrorDetails(e, url)
	}

	fun onRecycle() {
		state.value = PageState.Empty
		cachedBounds = null
		boundPage = null
		pendingLayerSwitchPageId = null
		boundsCache.clear()
		job?.cancel()
	}

	fun refreshDisplayVariant(page: ContentPage) {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			loader.invalidateTask(page.id)
			doLoad(page, force = false)
		}
	}

	fun switchDisplayLayer(page: ContentPage) {
		val currentState = state.value
		val source = when (currentState) {
			is PageState.Shown -> currentState.source
			is PageState.Loaded -> currentState.source
			is PageState.AwaitingTranslation -> currentState.source
			else -> null
		}
		val currentUri = (source as? ImageSource.Uri)?.uri
		if (currentUri == null) {
			return
		}
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			val targetUri = enhancementController.resolveDisplayVariant(
				page = page,
				currentUri = currentUri,
				showTranslated = settingsProducer.value.isTranslationShowTranslated,
			)
			if (targetUri == null || targetUri == currentUri) {
				if (settingsProducer.value.isTranslationEnabled && settingsProducer.value.isTranslationShowTranslated) {
					val isConverted = when (currentState) {
						is PageState.Shown -> currentState.isConverted
						is PageState.Loaded -> currentState.isConverted
						is PageState.AwaitingTranslation -> currentState.isConverted
						else -> false
					}
					state.value = PageState.AwaitingTranslation(
						source = currentUri.toImageSource(cachedBounds),
						isConverted = isConverted,
					)
				}
				return@launch
			}
			cachedBounds = resolveTrimmedBounds(targetUri)
			state.value = PageState.Loaded(targetUri.toImageSource(cachedBounds), isConverted = false)
		}
	}

	override fun onImageLoaded() {
		state.update { currentState ->
			if (currentState is PageState.Loaded) {
				PageState.Shown(currentState.source, currentState.isConverted)
			} else {
				currentState
			}
		}
	}

	override fun onImageLoadError(e: Throwable) {
		e.printStackTraceDebug()

		state.update { currentState ->
			if (currentState is PageState.Loaded) {
				val uri = (currentState.source as? ImageSource.Uri)?.uri
				if (!currentState.isConverted && uri != null && e is IOException) {
					tryConvert(uri, e)
					PageState.Converting()
				} else {
					PageState.Error(e)
				}
			} else {
				currentState
			}
		}
	}

	private fun isAnimatedImage(uri: Uri): Boolean {
		if (uri.scheme != "file") return false
		val path = uri.path ?: return false
		val file = java.io.File(path)
		if (!file.exists()) return false
		return try {
			file.inputStream().use { stream ->
				val header = ByteArray(6)
				stream.read(header)
				header[0] == 'G'.code.toByte() &&
				header[1] == 'I'.code.toByte() &&
				header[2] == 'F'.code.toByte()
			}
		} catch (e: Exception) {
			false
		}
	}

	private fun tryConvert(uri: Uri, e: Exception) {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.join()
			state.value = PageState.Converting()
			try {
				val newUri = loader.convertBimap(uri)
				cachedBounds = resolveTrimmedBounds(newUri)
				state.value = PageState.Loaded(newUri.toImageSource(cachedBounds), isConverted = true)
			} catch (ce: CancellationException) {
				throw ce
			} catch (e2: Throwable) {
				e2.printStackTrace()
				e.addSuppressed(e2)
				state.value = PageState.Error(e)
			}
		}
	}

	@WorkerThread
	private suspend fun doLoad(data: ContentPage, force: Boolean) = coroutineScope {
		state.value = PageState.Loading(null, -1)
		val previewJob = launch {
			val preview = loader.loadPreview(data) ?: return@launch
			state.update {
				if (it is PageState.Loading) it.copy(preview = preview) else it
			}
		}
		try {
			val task = loader.loadPageAsync(data, force)
			val progressObserver = observeProgress(this, task.progressAsFlow())
			val uri = task.await()
			progressObserver.cancelAndJoin()
			previewJob.cancel()
			val displayUri = enhancementController.resolveDisplayVariant(
				page = data,
				currentUri = uri,
				showTranslated = settingsProducer.value.isTranslationShowTranslated,
			) ?: uri
			if (isAnimatedImage(displayUri)) {
				state.value = PageState.LoadedAnimated(displayUri)
			} else {
				cachedBounds = resolveTrimmedBounds(displayUri)
				state.value = if (settingsProducer.value.isTranslationEnabled && settingsProducer.value.isTranslationShowTranslated && displayUri == uri) {
					PageState.AwaitingTranslation(displayUri.toImageSource(cachedBounds), isConverted = false)
				} else {
					PageState.Loaded(displayUri.toImageSource(cachedBounds), isConverted = false)
				}
			}
			applyPendingLayerSwitchIfNeeded(data, displayUri)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			state.value = PageState.Error(e)
			if (e is IOException && !networkState.value) {
				networkState.awaitForConnection()
				retry(data, isFromUser = false)
			}
		}
	}

	private fun observeProgress(scope: CoroutineScope, progress: Flow<Float>) = progress
		.throttle(250)
		.onEach {
			val progressValue = (100 * it).toInt()
			state.update { currentState ->
				if (currentState is PageState.Loading) {
					currentState.copy(progress = progressValue)
				} else {
					currentState
				}
			}
		}.launchIn(scope)

	private fun Uri.toImageSource(bounds: Rect?): ImageSource {
		val source = ImageSource.uri(this)
		return if (bounds != null) {
			source.region(bounds)
		} else {
			source
		}
	}

	private suspend fun applyPendingLayerSwitchIfNeeded(page: ContentPage, currentUri: Uri) {
		if (pendingLayerSwitchPageId != page.id) {
			return
		}
		pendingLayerSwitchPageId = null
		val targetUri = enhancementController.resolveDisplayVariant(
			page = page,
			currentUri = currentUri,
			showTranslated = settingsProducer.value.isTranslationShowTranslated,
		)
		if (targetUri == null || targetUri == currentUri) {
			return
		}
		cachedBounds = resolveTrimmedBounds(targetUri)
		state.value = PageState.Loaded(targetUri.toImageSource(cachedBounds), isConverted = false)
	}

	suspend fun resolveLayerSources(page: ContentPage): LayerSources? {
		val currentState = state.value
		val source = when (currentState) {
			is PageState.Shown -> currentState.source
			is PageState.Loaded -> currentState.source
			is PageState.AwaitingTranslation -> currentState.source
			else -> null
		}
		val currentUri = (source as? ImageSource.Uri)?.uri ?: return null
		val originalUri = enhancementController.resolveDisplayVariant(page, currentUri, showTranslated = false) ?: currentUri
		val translatedUri = enhancementController.resolveDisplayVariant(page, currentUri, showTranslated = true)
		val original = originalUri.toImageSource(resolveTrimmedBounds(originalUri))
		val translated = translatedUri?.let { it.toImageSource(resolveTrimmedBounds(it)) }
		return LayerSources(original = original, translated = translated)
	}

	private suspend fun resolveTrimmedBounds(uri: Uri): Rect? {
		val path = uri.path
		var options: BitmapFactory.Options? = null
		if (uri.scheme == "file" && path != null) {
			options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(path, options)
			// Threshold of 1.15 to consider as a double page
			if (options.outWidth > options.outHeight * 1.15) {
				boundPage?.let { loader.widePageDetectedEvent.tryEmit(it.id) }
			}
		}

		val cropBounds = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
			val splitPostfix = if (boundPageSplit != org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.NONE) "_" + boundPageSplit.name else ""
			val key = uri.toString() + splitPostfix
			if (boundsCache.containsKey(key)) {
				boundsCache[key]
			} else {
				val b = loader.getTrimmedBounds(uri)
				boundsCache[key] = b
				if (boundsCache.size > 64) {
					val eldest = boundsCache.entries.iterator().next().key
					boundsCache.remove(eldest)
				}
				b
			}
		} else null

		val baseBounds = cropBounds ?: options?.let { Rect(0, 0, it.outWidth, it.outHeight) }
		if (baseBounds != null && boundPageSplit != org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.NONE) {
			val halfWidth = baseBounds.width() / 2
			return if (boundPageSplit == org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.LEFT) {
				Rect(baseBounds.left, baseBounds.top, baseBounds.left + halfWidth, baseBounds.bottom)
			} else {
				Rect(baseBounds.left + halfWidth, baseBounds.top, baseBounds.right, baseBounds.bottom)
			}
		}
		return cropBounds
	}
}
