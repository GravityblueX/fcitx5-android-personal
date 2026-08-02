/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionCandidate
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionResponse
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingModelCallback
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupComponent
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
        private const val MODEL_QUERY_TIMEOUT_MS = 6_000L
        private const val MODEL_CHECK_TIMEOUT_ERROR = "TimeoutException"

        fun isHandwritingInputMethod(ime: InputMethodEntry): Boolean =
            ime.addon == ADDON_NAME
    }

    override val key: EssentialWindow.Key
        get() = HandwritingWindow

    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val bar: KawaiiBarComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()
    private val popup: PopupComponent by manager.must()
    private val requestIds = AtomicLong()
    private val modePreference = AppPrefs.getInstance().handwriting.recognitionMode

    private lateinit var canvas: HandwritingCanvas
    private lateinit var downloadButton: ToolButton
    private lateinit var statusText: TextView
    private lateinit var handwritingKeyboard: HandwritingKeyboard
    private lateinit var controlKeyboard: HandwritingControlKeyboard

    private var currentIme: InputMethodEntry? = null
    private var currentMode = modePreference.getValue().protocolMode
    private var activeRequestId = 0L
    private var contentScale = 1f
    private var modelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
    private var modelStateError = ""
    private var modelQueryGeneration = 0L
    private var attached = false
    private var recognitionCandidates = emptyList<HandwritingRecognitionCandidate>()
    private var recognitionAllowed = true

    @Keep
    private val modeChangeListener =
        ManagedPreference.OnChangeListener<HandwritingRecognitionMode> { _, mode ->
            applyRecognitionMode(mode)
        }

    private val keyActionListener = KeyActionListener { action, source ->
        commonKeyActionListener.listener.onKeyAction(action, source)
    }
    private val providerChangeListener = {
        ContextCompat.getMainExecutor(service).execute {
            if (attached) {
                queryModelState()
            }
        }
    }

    override fun onCreateView(): View {
        canvas = HandwritingCanvas(context, theme.keyTextColor) {
            updateStatus()
            recognize()
        }
        canvas.isEnabled = recognitionAllowed
        downloadButton = actionButton(
            R.drawable.ic_baseline_download_24,
            context.getString(R.string.handwriting_download_model),
            ::handleModelAction,
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
        controlKeyboard = HandwritingControlKeyboard(
            context,
            theme,
            ::undo,
            ::clear,
        ).apply {
            keyActionListener = this@HandwritingWindow.keyActionListener
        }
        val writingSurface = FrameLayout(context).apply {
            addView(
                canvas,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                statusPanel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val writingArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                writingSurface,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f - HandwritingKeyboard.RETURN_KEY_WIDTH_FRACTION,
                ),
            )
            addView(
                controlKeyboard,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    HandwritingKeyboard.RETURN_KEY_WIDTH_FRACTION,
                ),
            )
        }
        handwritingKeyboard = HandwritingKeyboard(context, theme).apply {
            keyActionListener = this@HandwritingWindow.keyActionListener
            popupActionListener = popup.listener
            onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            onRecognitionModeUpdate(HandwritingRecognitionMode.fromProtocolMode(currentMode))
            currentIme?.let(::onInputMethodUpdate)
        }
        updateStatus()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.backgroundDrawable()
            addView(
                writingArea,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    (TextKeyboard.Layout.size - 1).toFloat(),
                ),
            )
            addView(
                handwritingKeyboard,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentIme = ime
        if (!isHandwritingInputMethod(ime)) {
            resetInputState()
            return
        }
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.onInputMethodUpdate(ime)
            applyRecognitionMode(modePreference.getValue())
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        recognitionAllowed = HandwritingPrivacyPolicy.canRecognize(capFlags)
        if (::canvas.isInitialized) {
            canvas.isEnabled = recognitionAllowed
        }
        resetInputState()
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.onReturnDrawableUpdate(resourceId)
        }
    }

    override fun onAttached() {
        attached = true
        HandwritingProviderRegistry.addOnChangeListener(providerChangeListener)
        modePreference.registerOnChangeListener(modeChangeListener)
        applyRecognitionMode(modePreference.getValue())
        bar.onKeyboardLayoutSwitched(false)
        publishCandidates()
        handwritingKeyboard.keyActionListener = keyActionListener
        handwritingKeyboard.popupActionListener = popup.listener
        handwritingKeyboard.onAttach()
        controlKeyboard.keyActionListener = keyActionListener
        controlKeyboard.onAttach()
        currentIme?.let(handwritingKeyboard::onInputMethodUpdate)
    }

    override fun onDetached() {
        attached = false
        modelQueryGeneration++
        HandwritingProviderRegistry.removeOnChangeListener(providerChangeListener)
        modePreference.unregisterOnChangeListener(modeChangeListener)
        activeRequestId = requestIds.incrementAndGet()
        handwritingKeyboard.onDetach()
        handwritingKeyboard.keyActionListener = null
        handwritingKeyboard.popupActionListener = null
        controlKeyboard.onDetach()
        controlKeyboard.keyActionListener = null
        popup.dismissAll()
    }

    override fun setContentScale(scale: Float) {
        if (contentScale == scale) return
        contentScale = scale
        if (!::canvas.isInitialized) return
        canvas.setContentScale(scale)
        downloadButton.setContentScale(scale)
        handwritingKeyboard.setContentScale(scale)
        controlKeyboard.setContentScale(scale)
        statusText.textSize = STATUS_TEXT_SIZE_SP * scale.coerceAtLeast(0.75f)
    }

    override fun setUsePortraitKeyboardStyle(enabled: Boolean) {
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.setUsePortraitStyle(enabled)
            controlKeyboard.setUsePortraitStyle(enabled)
        }
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.onPunctuationUpdate(mapping)
        }
    }

    fun setRecognitionMode(mode: HandwritingRecognitionMode) {
        if (modePreference.getValue() != mode) {
            modePreference.setValue(mode)
        }
        applyRecognitionMode(mode)
    }

    private fun applyRecognitionMode(mode: HandwritingRecognitionMode) {
        val newMode = mode.protocolMode
        if (newMode != currentMode) {
            currentMode = newMode
            activeRequestId = requestIds.incrementAndGet()
            if (::canvas.isInitialized) {
                canvas.clear()
                updateCandidates(emptyList())
            }
            modelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
            modelStateError = ""
        }
        if (::handwritingKeyboard.isInitialized) {
            handwritingKeyboard.onRecognitionModeUpdate(mode)
        }
        if (attached) {
            queryModelState()
        } else {
            updateStatus()
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
        val queryGeneration = ++modelQueryGeneration
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            showProviderUnavailable()
            return
        }
        if (modelState == HandwritingProtocol.MODEL_STATE_UNKNOWN) {
            scheduleModelQueryTimeout(queryGeneration)
        }
        try {
            provider.remote.queryModelState(
                currentMode,
                modelCallback(currentMode, queryGeneration),
            )
        } catch (e: Exception) {
            Timber.w(e, "Handwriting provider %s is unavailable", provider.id)
            showProviderUnavailable()
        }
    }

    fun reloadRecognitionEngine(onComplete: ((Boolean) -> Unit)? = null) {
        modelQueryGeneration++
        modelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
        modelStateError = ""
        updateCandidates(emptyList())
        updateStatus()
        if (!HandwritingProviderRegistry.reloadBuiltIn { engineReady ->
            ContextCompat.getMainExecutor(service).execute {
                if (attached) {
                    if (engineReady) {
                        queryModelState()
                    } else {
                        showProviderUnavailable()
                    }
                }
                onComplete?.invoke(engineReady)
            }
        }) {
            showProviderUnavailable()
            onComplete?.invoke(false)
        }
    }

    fun refreshModels(onComplete: ((Boolean) -> Unit)? = null) {
        if (!::statusText.isInitialized) {
            Timber.w("Model refresh ignored because the handwriting view is not initialized")
            onComplete?.invoke(false)
            return
        }
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            Timber.w("Model refresh failed because no provider supports mode %s", currentMode)
            showProviderUnavailable()
            onComplete?.invoke(false)
            return
        }
        Timber.d(
            "Model refresh requested for mode %s from %s; attached=%s",
            currentMode,
            provider.id,
            attached,
        )
        val queryGeneration = ++modelQueryGeneration
        var completionDelivered = false
        fun completeOnce(success: Boolean) {
            if (completionDelivered) return
            completionDelivered = true
            onComplete?.invoke(success)
        }
        modelState = HandwritingProtocol.MODEL_STATE_UNKNOWN
        modelStateError = ""
        updateCandidates(emptyList())
        updateStatus()
        scheduleModelQueryTimeout(queryGeneration) {
            Timber.w("Model refresh timed out for mode %s", currentMode)
            completeOnce(false)
        }
        try {
            Timber.d("Sending model refresh IPC for mode %s", currentMode)
            provider.remote.refreshModelState(
                currentMode,
                modelCallback(currentMode, queryGeneration) { state ->
                    Timber.d("Model refresh settled with state=%s", state)
                    completeOnce(state != HandwritingProtocol.MODEL_STATE_FAILED)
                },
            )
        } catch (e: Exception) {
            Timber.w(e, "Cannot refresh handwriting model state from %s", provider.id)
            updateModelState(HandwritingProtocol.MODEL_STATE_FAILED)
            completeOnce(false)
        }
    }

    private fun scheduleModelQueryTimeout(
        queryGeneration: Long,
        onTimeout: (() -> Unit)? = null,
    ) {
        statusText.postDelayed(
            {
                if (queryGeneration == modelQueryGeneration &&
                    modelState == HandwritingProtocol.MODEL_STATE_UNKNOWN
                ) {
                    updateModelState(
                        HandwritingProtocol.MODEL_STATE_FAILED,
                        MODEL_CHECK_TIMEOUT_ERROR,
                    )
                    onTimeout?.invoke()
                }
            },
            MODEL_QUERY_TIMEOUT_MS,
        )
    }

    private fun handleModelAction() {
        if (modelStateError == MODEL_CHECK_TIMEOUT_ERROR) {
            refreshModels()
        } else {
            downloadModel()
        }
    }

    private fun downloadModel() {
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            showProviderUnavailable()
            return
        }
        val queryGeneration = ++modelQueryGeneration
        updateModelState(HandwritingProtocol.MODEL_STATE_DOWNLOADING)
        try {
            provider.remote.downloadModel(
                currentMode,
                false,
                modelCallback(currentMode, queryGeneration),
            )
        } catch (e: Exception) {
            Timber.w(e, "Cannot request handwriting model download from %s", provider.id)
            updateModelState(HandwritingProtocol.MODEL_STATE_FAILED)
        }
    }

    private fun modelCallback(
        mode: Int,
        queryGeneration: Long,
        onSettled: ((Int) -> Unit)? = null,
    ) = object : IHandwritingModelCallback.Stub() {
        override fun onState(modeFromProvider: Int, state: Int, errorMessage: String) {
            ContextCompat.getMainExecutor(service).execute {
                if (mode != currentMode ||
                    modeFromProvider != currentMode ||
                    queryGeneration != modelQueryGeneration
                ) {
                    return@execute
                }
                if (errorMessage.isNotBlank()) {
                    Timber.w("Handwriting model state query failed: %s", errorMessage)
                }
                updateModelState(state, errorMessage)
                if (state != HandwritingProtocol.MODEL_STATE_UNKNOWN &&
                    state != HandwritingProtocol.MODEL_STATE_DOWNLOADING
                ) {
                    onSettled?.invoke(state)
                }
                if (state == HandwritingProtocol.MODEL_STATE_READY && canvas.hasInk) {
                    recognize()
                }
            }
        }
    }

    private fun recognize() {
        if (!recognitionAllowed || !::canvas.isInitialized || !canvas.hasInk) return
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            updateCandidates(emptyList())
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
                            updateCandidates(response.candidates)
                        }
                        HandwritingProtocol.ERROR_MODEL_NOT_DOWNLOADED -> {
                            updateCandidates(emptyList())
                            updateModelState(HandwritingProtocol.MODEL_STATE_NOT_DOWNLOADED)
                        }
                        else -> {
                            updateCandidates(emptyList())
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
            updateCandidates(emptyList())
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
        updateCandidates(emptyList())
        updateStatus()
        if (canvas.hasInk && modelState == HandwritingProtocol.MODEL_STATE_READY) {
            recognize()
        }
    }

    private fun clear() {
        activeRequestId = requestIds.incrementAndGet()
        canvas.clear()
        updateCandidates(emptyList())
        updateStatus()
    }

    private fun updateCandidates(candidates: List<HandwritingRecognitionCandidate>) {
        recognitionCandidates = candidates
        if (attached || horizontalCandidate.isCandidateOverrideActive) {
            publishCandidates()
        }
    }

    private fun publishCandidates() {
        val publishedCandidates = recognitionCandidates.toList()
        horizontalCandidate.setCandidateOverride(
            candidates = publishedCandidates.map {
                CandidateWord("", it.text, "")
            }.toTypedArray(),
            onCandidateClick = { index ->
                publishedCandidates.getOrNull(index)?.let(::commitCandidate)
            },
            onClear = ::clear,
        )
    }

    private fun resetInputState() {
        activeRequestId = requestIds.incrementAndGet()
        if (::canvas.isInitialized) {
            canvas.clear()
        }
        recognitionCandidates = emptyList()
        horizontalCandidate.clearCandidateOverride()
        updateStatus()
    }

    private fun updateModelState(
        state: Int,
        errorMessage: String = "",
    ) {
        modelState = state
        modelStateError = errorMessage
        updateStatus()
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return
        if (!recognitionAllowed) {
            downloadButton.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            statusText.text = context.getString(R.string.handwriting_sensitive_input_unavailable)
            return
        }
        downloadButton.visibility = View.GONE
        downloadButton.setIcon(R.drawable.ic_baseline_download_24)
        downloadButton.contentDescription =
            context.getString(R.string.handwriting_download_model)
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
                if (modelStateError == MODEL_CHECK_TIMEOUT_ERROR) {
                    downloadButton.setIcon(R.drawable.ic_baseline_sync_24)
                    downloadButton.contentDescription =
                        context.getString(R.string.handwriting_refresh_models)
                    context.getString(R.string.handwriting_model_check_timed_out)
                } else {
                    context.getString(R.string.handwriting_model_failed)
                }
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
        modelStateError = ""
        downloadButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = context.getString(R.string.handwriting_provider_unavailable)
    }

    private fun showRecognitionFailed() {
        statusText.visibility = View.VISIBLE
        statusText.text = context.getString(R.string.handwriting_recognition_failed)
    }

}
