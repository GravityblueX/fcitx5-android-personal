/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionCandidate
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionResponse
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.ScalableInputWindow
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

class HandwritingWindow :
    InputWindow.SimpleInputWindow<HandwritingWindow>(),
    EssentialWindow,
    InputBroadcastReceiver,
    ScalableInputWindow {

    companion object : EssentialWindow.Key {
        const val ADDON_NAME = "androidhandwriting"
        private const val STATUS_TEXT_SIZE_SP = 16f
        private const val MAX_PRE_CONTEXT_LENGTH = 20

        fun isHandwritingInputMethod(ime: InputMethodEntry): Boolean =
            ime.addon == ADDON_NAME
    }

    override val key: EssentialWindow.Key
        get() = HandwritingWindow

    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val bar: KawaiiBarComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()
    private val requestIds = AtomicLong()

    private lateinit var canvas: HandwritingCanvas
    private lateinit var candidateView: RecyclerView
    private lateinit var recognizeButton: ToolButton
    private lateinit var undoButton: ToolButton
    private lateinit var clearButton: ToolButton
    private lateinit var downloadButton: ToolButton
    private lateinit var statusText: TextView
    private lateinit var handwritingKeyboard: HandwritingKeyboard

    private var currentIme: InputMethodEntry? = null
    private var currentMode = HandwritingProtocol.MODE_CHINESE_SIMPLIFIED
    private var activeRequestId = 0L
    private var contentScale = 1f
    private var modelState = HandwritingProtocol.MODEL_STATE_UNKNOWN

    private val keyActionListener = KeyActionListener { action, source ->
        commonKeyActionListener.listener.onKeyAction(action, source)
    }

    private val candidateAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            private var recognitionCandidates = emptyList<HandwritingRecognitionCandidate>()

            fun update(data: List<HandwritingRecognitionCandidate>) {
                recognitionCandidates = data
                updateCandidates(
                    data.map {
                        CandidateWord("", it.text, "")
                    }.toTypedArray(),
                    data.size,
                )
            }

            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.ui.setContentScale(contentScale)
                holder.itemView.setOnClickListener {
                    recognitionCandidates.getOrNull(holder.idx)?.let(::commitCandidate)
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    override fun onCreateView(): View {
        canvas = HandwritingCanvas(context, theme.keyTextColor) {
            updateStatus()
            recognize()
        }
        candidateView = RecyclerView(context).apply {
            itemAnimator = null
            adapter = candidateAdapter
            layoutManager = object : FlexboxLayoutManager(context) {
                override fun canScrollVertically() = false
            }
        }
        recognizeButton = actionButton(
            R.drawable.ic_baseline_done_24,
            context.getString(R.string.done),
            ::recognize,
        )
        undoButton = actionButton(
            R.drawable.ic_baseline_undo_24,
            context.getString(R.string.undo),
            ::undo,
        )
        clearButton = actionButton(
            R.drawable.ic_baseline_delete_sweep_24,
            context.getString(R.string.clear),
            ::clear,
        )
        downloadButton = actionButton(
            R.drawable.ic_baseline_download_24,
            context.getString(R.string.handwriting_download_model),
            ::downloadModel,
        )
        statusText = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(theme.keyTextColor)
            textSize = STATUS_TEXT_SIZE_SP
            alpha = 0.62f
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val statusPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                downloadButton,
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(recognizeButton, LinearLayout.LayoutParams(dp(44), 0, 1f))
            addView(undoButton, LinearLayout.LayoutParams(dp(44), 0, 1f))
            addView(clearButton, LinearLayout.LayoutParams(dp(44), 0, 1f))
        }
        val writingArea = FrameLayout(context).apply {
            addView(
                canvas,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    marginEnd = dp(44)
                },
            )
            addView(
                statusPanel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    marginEnd = dp(44)
                },
            )
            addView(
                controls,
                FrameLayout.LayoutParams(
                    dp(44),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END,
                ),
            )
        }
        handwritingKeyboard = HandwritingKeyboard(context, theme).apply {
            keyActionListener = this@HandwritingWindow.keyActionListener
            onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            currentIme?.let(::onInputMethodUpdate)
        }
        updateStatus()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.backgroundDrawable()
            addView(
                candidateView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                ),
            )
            addView(
                writingArea,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                handwritingKeyboard,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(64),
                ),
            )
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentIme = ime
        if (!isHandwritingInputMethod(ime)) return
        val newMode = when (ime.uniqueName) {
            "handwriting-en" -> HandwritingProtocol.MODE_ENGLISH
            "handwriting-ja" -> HandwritingProtocol.MODE_JAPANESE
            "handwriting-auto" -> HandwritingProtocol.MODE_AUTO
            else -> HandwritingProtocol.MODE_CHINESE_SIMPLIFIED
        }
        if (newMode != currentMode && ::canvas.isInitialized) {
            activeRequestId = requestIds.incrementAndGet()
            canvas.clear()
            candidateAdapter.update(emptyList())
        }
        currentMode = newMode
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.onInputMethodUpdate(ime)
            queryModelState()
        }
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.onReturnDrawableUpdate(resourceId)
        }
    }

    override fun onAttached() {
        bar.onKeyboardLayoutSwitched(false)
        handwritingKeyboard.keyActionListener = keyActionListener
        currentIme?.let(handwritingKeyboard::onInputMethodUpdate)
        queryModelState()
    }

    override fun onDetached() {
        activeRequestId = requestIds.incrementAndGet()
        handwritingKeyboard.keyActionListener = null
        canvas.clear()
        candidateAdapter.update(emptyList())
        updateStatus()
    }

    override fun setContentScale(scale: Float) {
        if (contentScale == scale) return
        contentScale = scale
        if (!::canvas.isInitialized) return
        canvas.setContentScale(scale)
        recognizeButton.setContentScale(scale)
        undoButton.setContentScale(scale)
        clearButton.setContentScale(scale)
        downloadButton.setContentScale(scale)
        handwritingKeyboard.setContentScale(scale)
        statusText.textSize = STATUS_TEXT_SIZE_SP * scale.coerceAtLeast(0.75f)
        candidateAdapter.notifyItemRangeChanged(0, candidateAdapter.itemCount)
    }

    override fun setUsePortraitKeyboardStyle(enabled: Boolean) {
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.setUsePortraitStyle(enabled)
        }
    }

    private fun actionButton(
        icon: Int,
        description: String,
        onClick: () -> Unit,
    ) = ToolButton(context, icon, theme).apply {
        contentDescription = description
        setBoundedPressHighlightColor(theme.keyPressHighlightColor)
        setOnClickListener { onClick() }
    }

    private fun queryModelState() {
        if (!::statusText.isInitialized) return
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            showProviderUnavailable()
            return
        }
        updateModelState(HandwritingProtocol.MODEL_STATE_UNKNOWN)
        try {
            provider.remote.queryModelState(currentMode, modelCallback(currentMode))
        } catch (e: Exception) {
            Timber.w(e, "Handwriting provider %s is unavailable", provider.id)
            showProviderUnavailable()
        }
    }

    private fun downloadModel() {
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            showProviderUnavailable()
            return
        }
        updateModelState(HandwritingProtocol.MODEL_STATE_DOWNLOADING)
        try {
            provider.remote.downloadModel(
                currentMode,
                false,
                modelCallback(currentMode),
            )
        } catch (e: Exception) {
            Timber.w(e, "Cannot request handwriting model download from %s", provider.id)
            updateModelState(HandwritingProtocol.MODEL_STATE_FAILED)
        }
    }

    private fun modelCallback(mode: Int) = object : IHandwritingModelCallback.Stub() {
        override fun onState(modeFromProvider: Int, state: Int, errorMessage: String) {
            ContextCompat.getMainExecutor(service).execute {
                if (mode != currentMode || modeFromProvider != currentMode) return@execute
                updateModelState(state)
                if (state == HandwritingProtocol.MODEL_STATE_READY && canvas.hasInk) {
                    recognize()
                }
            }
        }
    }

    private fun recognize() {
        if (!::canvas.isInitialized || !canvas.hasInk) return
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            candidateAdapter.update(emptyList())
            showProviderUnavailable()
            return
        }
        val requestId = requestIds.incrementAndGet()
        activeRequestId = requestId
        val request = HandwritingRecognitionRequest(
            requestId = requestId,
            mode = currentMode,
            strokes = canvas.snapshot(),
            canvasWidth = canvas.width.toFloat(),
            canvasHeight = canvas.height.toFloat(),
            preContext = runCatching {
                service.currentInputConnection
                    ?.getTextBeforeCursor(MAX_PRE_CONTEXT_LENGTH, 0)
                    ?.toString()
                    .orEmpty()
            }.getOrDefault(""),
            maxCandidates = 8,
        )
        val callback = object : IHandwritingRecognitionCallback.Stub() {
            override fun onResult(response: HandwritingRecognitionResponse) {
                ContextCompat.getMainExecutor(service).execute {
                    if (response.requestId != activeRequestId) return@execute
                    when (response.errorCode) {
                        HandwritingProtocol.ERROR_NONE -> {
                            updateModelState(HandwritingProtocol.MODEL_STATE_READY)
                            candidateAdapter.update(response.candidates)
                        }
                        HandwritingProtocol.ERROR_MODEL_NOT_DOWNLOADED -> {
                            candidateAdapter.update(emptyList())
                            updateModelState(HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED)
                        }
                        else -> {
                            candidateAdapter.update(emptyList())
                            showRecognitionFailed()
                        }
                    }
                }
            }
        }
        try {
            provider.remote.recognize(request, callback)
        } catch (e: Exception) {
            Timber.w(e, "Handwriting recognition provider %s is unavailable", provider.id)
            candidateAdapter.update(emptyList())
            showProviderUnavailable()
        }
    }

    private fun commitCandidate(candidate: HandwritingRecognitionCandidate) {
        service.commitText(candidate.text)
        HandwritingProviderRegistry.select(currentMode)?.let { provider ->
            runCatching {
                provider.remote.notifyCandidateSelected(currentMode, candidate.languageTag)
            }.onFailure {
                Timber.w(it, "Cannot notify handwriting provider about selected candidate")
            }
        }
        clear()
    }

    private fun undo() {
        if (!canvas.undo()) return
        activeRequestId = requestIds.incrementAndGet()
        candidateAdapter.update(emptyList())
        updateStatus()
        if (canvas.hasInk && modelState == HandwritingProtocol.MODEL_STATE_READY) {
            recognize()
        }
    }

    private fun clear() {
        activeRequestId = requestIds.incrementAndGet()
        canvas.clear()
        candidateAdapter.update(emptyList())
        updateStatus()
    }

    private fun updateModelState(state: Int) {
        modelState = state
        updateStatus()
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        downloadButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = when (modelState) {
            HandwritingProtocol.MODEL_STATE_READY -> {
                if (canvas.hasInk) {
                    statusText.visibility = View.GONE
                }
                context.getString(R.string.handwriting_hint)
            }
            HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED -> {
                downloadButton.visibility = View.VISIBLE
                context.getString(
                    R.string.handwriting_model_missing,
                    currentModelName(),
                    currentModelDownloadSizeMb(),
                )
            }
            HandwritingProtocol.MODEL_STATE_DOWNLOADING ->
                context.getString(
                    R.string.handwriting_model_downloading,
                    currentModelName(),
                )
            HandwritingProtocol.MODEL_STATE_FAILED -> {
                downloadButton.visibility = View.VISIBLE
                context.getString(R.string.handwriting_model_failed)
            }
            else ->
                context.getString(
                    R.string.handwriting_model_checking,
                    currentModelName(),
                )
        }
    }

    private fun currentModelName(): String = context.getString(
        when (currentMode) {
            HandwritingProtocol.MODE_ENGLISH ->
                R.string.handwriting_model_name_english
            HandwritingProtocol.MODE_JAPANESE ->
                R.string.handwriting_model_name_japanese
            HandwritingProtocol.MODE_AUTO ->
                R.string.handwriting_model_name_auto
            else ->
                R.string.handwriting_model_name_chinese
        }
    )

    private fun currentModelDownloadSizeMb(): Int =
        if (currentMode == HandwritingProtocol.MODE_AUTO) 60 else 20

    private fun showProviderUnavailable() {
        modelState = HandwritingProtocol.MODEL_STATE_FAILED
        downloadButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = context.getString(R.string.handwriting_provider_unavailable)
    }

    private fun showRecognitionFailed() {
        statusText.visibility = View.VISIBLE
        statusText.text = context.getString(R.string.handwriting_recognition_failed)
    }

}
