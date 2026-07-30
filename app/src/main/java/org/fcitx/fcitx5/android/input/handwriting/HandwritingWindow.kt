/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionCandidate
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionRequest
import org.fcitx.fcitx5.android.common.handwriting.HandwritingRecognitionResponse
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionCallback
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
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

        fun isHandwritingInputMethod(ime: InputMethodEntry): Boolean =
            ime.addon == ADDON_NAME
    }

    override val key: EssentialWindow.Key
        get() = HandwritingWindow

    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val bar: KawaiiBarComponent by manager.must()
    private val requestIds = AtomicLong()

    private lateinit var canvas: HandwritingCanvas
    private lateinit var candidateView: RecyclerView
    private lateinit var recognizeButton: ToolButton
    private lateinit var clearButton: ToolButton

    private var currentMode = HandwritingProtocol.MODE_CHINESE_SIMPLIFIED
    private var activeRequestId = 0L
    private var contentScale = 1f

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
                    recognitionCandidates.getOrNull(holder.idx)?.text?.let(::commitCandidate)
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    override fun onCreateView(): View {
        canvas = HandwritingCanvas(context, theme.keyTextColor, ::recognize)
        candidateView = RecyclerView(context).apply {
            itemAnimator = null
            adapter = candidateAdapter
            layoutManager = object : FlexboxLayoutManager(context) {
                override fun canScrollVertically() = false
            }
        }
        recognizeButton = ToolButton(context, R.drawable.ic_baseline_done_24, theme).apply {
            contentDescription = context.getString(R.string.done)
            setBoundedPressHighlightColor(theme.keyPressHighlightColor)
            setOnClickListener { recognize() }
        }
        clearButton = ToolButton(context, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
            contentDescription = context.getString(R.string.clear)
            setBoundedPressHighlightColor(theme.keyPressHighlightColor)
            setOnClickListener { clear() }
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                recognizeButton,
                LinearLayout.LayoutParams(dp(48), 0, 1f),
            )
            addView(
                clearButton,
                LinearLayout.LayoutParams(dp(48), 0, 1f),
            )
        }
        val writingArea = FrameLayout(context).apply {
            addView(
                canvas,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    marginEnd = dp(48)
                },
            )
            addView(
                controls,
                FrameLayout.LayoutParams(
                    dp(48),
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END,
                ),
            )
        }
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
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        if (isHandwritingInputMethod(ime)) {
            currentMode = when (ime.uniqueName) {
                "handwriting-en" -> HandwritingProtocol.MODE_ENGLISH
                "handwriting-ja" -> HandwritingProtocol.MODE_JAPANESE
                "handwriting-auto" -> HandwritingProtocol.MODE_AUTO
                else -> HandwritingProtocol.MODE_CHINESE_SIMPLIFIED
            }
        }
    }

    override fun onAttached() {
        bar.onKeyboardLayoutSwitched(false)
    }

    override fun onDetached() {
        activeRequestId = requestIds.incrementAndGet()
        canvas.clear()
        candidateAdapter.update(emptyList())
    }

    override fun setContentScale(scale: Float) {
        if (contentScale == scale) return
        contentScale = scale
        if (!::canvas.isInitialized) return
        canvas.setContentScale(scale)
        recognizeButton.setContentScale(scale)
        clearButton.setContentScale(scale)
        candidateAdapter.notifyItemRangeChanged(0, candidateAdapter.itemCount)
    }

    private fun recognize() {
        if (!::canvas.isInitialized) return
        val provider = HandwritingProviderRegistry.select(currentMode) ?: run {
            candidateAdapter.update(emptyList())
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
            preContext = "",
            maxCandidates = 8,
        )
        val callback = object : IHandwritingRecognitionCallback.Stub() {
            override fun onResult(response: HandwritingRecognitionResponse) {
                ContextCompat.getMainExecutor(service).execute {
                    if (response.requestId != activeRequestId) return@execute
                    if (response.errorCode == HandwritingProtocol.ERROR_NONE) {
                        candidateAdapter.update(response.candidates)
                    } else {
                        candidateAdapter.update(emptyList())
                    }
                }
            }
        }
        try {
            provider.remote.recognize(request, callback)
        } catch (e: Exception) {
            Timber.w(e, "Handwriting recognition provider %s is unavailable", provider.id)
            candidateAdapter.update(emptyList())
        }
    }

    private fun commitCandidate(text: String) {
        service.commitText(text)
        clear()
    }

    private fun clear() {
        activeRequestId = requestIds.incrementAndGet()
        canvas.clear()
        candidateAdapter.update(emptyList())
    }
}
