package org.skepsun.kototoro.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.target
import coil3.util.CoilUtils
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.isLowRamDevice
import org.skepsun.kototoro.core.util.ext.isSerializable
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.databinding.LayoutPageInfoBinding
import org.skepsun.kototoro.parsers.util.ifZero
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.ui.config.ReaderSettings
import org.skepsun.kototoro.reader.ui.pager.vm.PageState
import org.skepsun.kototoro.reader.ui.pager.vm.PageViewModel
import org.skepsun.kototoro.reader.ui.pager.webtoon.WebtoonHolder

abstract class BasePageHolder<B : ViewBinding>(
	protected val binding: B,
	loader: PageLoader,
	enhancementController: ReaderPageEnhancementController,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	lifecycleOwner: LifecycleOwner,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

	private val coil: ImageLoader = loader.coil
	protected val viewModel = PageViewModel(
		loader = loader,
		enhancementController = enhancementController,
		settingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		isWebtoon = this is WebtoonHolder,
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	protected abstract val ssiv: SubsamplingScaleImageView
	protected abstract val imageAnimated: android.widget.ImageView

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

	val context: Context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set
	private var lastTranslationDisplaySignature: String? = null
	private var lastTranslationContentSignature: String? = null

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toContentPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		val translationDisplaySignature = settings.translationDisplaySignature()
		val translationContentSignature = settings.translationContentSignature()
		val translationDisplayChanged = lastTranslationDisplaySignature != null &&
			lastTranslationDisplaySignature != translationDisplaySignature
		val translationContentChanged = lastTranslationContentSignature != null &&
			lastTranslationContentSignature != translationContentSignature
		lastTranslationDisplaySignature = translationDisplaySignature
		lastTranslationContentSignature = translationContentSignature
		settings.applyBackground(itemView)
		if (translationDisplayChanged || translationContentChanged) {
			val page = boundData?.toContentPage()
			if (page != null) {
				if (translationDisplayChanged && !translationContentChanged) {
					viewModel.switchDisplayLayer(page)
				} else {
					viewModel.refreshDisplayVariant(page)
				}
			} else {
				reloadImage()
			}
		} else if (settings.applyBitmapConfig(ssiv)) {
			reloadImage()
		} else if (viewModel.state.value is PageState.Shown) {
			onReady()
		}
		ssiv.applyDownSampling(isResumed())
	}

	fun reloadImage() {
		val source = when (val state = viewModel.state.value) {
			is PageState.Shown -> state.source
			is PageState.AwaitingTranslation -> state.source
			else -> null
		} ?: return
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		boundData = data
		viewModel.onBind(data.toContentPage(), data.split)
		onBind(data)
	}

	@CallSuper
	protected open fun onBind(data: ReaderPage) = Unit

	override fun onCreate() {
		super.onCreate()
		context.registerComponentCallbacks(this)
		viewModel.state.observe(this, ::onStateChanged)
		viewModel.settingsProducer.observe(this, ::onConfigChanged)
	}

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toContentPage(), isFromUser = false) }
		}
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		viewModel.onRecycle()
		ssiv.recycle()
		CoilUtils.dispose(imageAnimated)
		imageAnimated.setImageDrawable(null)
		imageAnimated.isVisible = false
		ssiv.isVisible = true
	}

	override fun onTrimMemory(level: Int) {
		// TODO
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	protected open fun onStateChanged(state: PageState) {
		bindingInfo.layoutError.isVisible = state is PageState.Error
		bindingInfo.layoutProgress.isGone = state.isFinalState()
		val progress = (state as? PageState.Loading)?.progress ?: -1
		if (progress in 0..100) {
			bindingInfo.progressBar.isIndeterminate = false
			bindingInfo.progressBar.setProgressCompat(progress, true)
			bindingInfo.textViewStatus.text = context.getString(R.string.percent_string_pattern, progress.toString())
		} else {
			bindingInfo.progressBar.isIndeterminate = true
			bindingInfo.textViewStatus.setText(R.string.loading_)
		}
		when (state) {

			is PageState.LoadedAnimated -> {
				bindingInfo.layoutProgress.isGone = true
				ssiv.isVisible = false
				imageAnimated.isVisible = true
				val request = ImageRequest.Builder(context)
					.data(state.uri)
					.target(imageAnimated)
					.listener(
						onSuccess = { _: ImageRequest, _: SuccessResult ->
							viewModel.state.value = PageState.ShownAnimated(state.uri)
						},
						onError = { _: ImageRequest, result: ErrorResult ->
							viewModel.state.value = PageState.Error(result.throwable)
						},
					)
					.build()
				coil.enqueue(request)
			}

			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
			}

			is PageState.AwaitingTranslation -> {
				bindingInfo.layoutProgress.isGone = true
				ssiv.setImage(state.source)
			}

			is PageState.ShownAnimated -> {
				
			}

			is PageState.Empty -> Unit

			is PageState.Error -> {
				val e = state.error
				bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
				bindingInfo.buttonRetry.setText(
					ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
				)
				bindingInfo.buttonErrorDetails.isVisible = e.isSerializable()
				bindingInfo.layoutError.isVisible = true
				bindingInfo.progressBar.hide()
			}

			is PageState.Loaded -> {
				imageAnimated.isVisible = false
				ssiv.isVisible = true
				bindingInfo.textViewStatus.setText(R.string.preparing_)
				ssiv.setImage(state.source)
			}

			is PageState.Loading -> {
				if (state.preview != null && ssiv.getState() == null) {
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> Unit
		}
	}

	protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
		downSampling = when {
			isForeground || !settings.isReaderOptimizationEnabled -> 1
			BuildConfig.DEBUG -> 32
			context.isLowRamDevice() -> 8
			else -> 4
		}
	}
}
