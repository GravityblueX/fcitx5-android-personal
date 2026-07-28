/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
class InputView internal constructor(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme,
    floatingKeyboardSessionState: FloatingKeyboardSessionState
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val floatingPanelBackground = theme.backgroundDrawable(keyBorder)

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape
    private var navBarBottomInset = 0

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val portraitKeyboardContentWidthPx: Int
        get() {
            val metrics = resources.displayMetrics
            val portraitDisplayWidth = min(metrics.widthPixels, metrics.heightPixels)
            return (
                portraitDisplayWidth - 2 * dp(keyboardSidePadding.getValue())
                ).coerceAtLeast(1)
        }

    private val portraitKeyboardHeightPx: Int
        get() {
            val metrics = resources.displayMetrics
            val portraitDisplayHeight = max(metrics.widthPixels, metrics.heightPixels)
            return (
                portraitDisplayHeight * keyboardHeightPercent.getValue() / 100f
                ).toInt().coerceAtLeast(1)
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View
    private val keyboardPanel: View
    private val floatingControls: View
    private val floatingDragHandle: View
    private val floatingResizeHandle: View
    private val floatingKeyboardLocation = IntArray(2)
    private var floatingController: FloatingKeyboardController? = null
    private var floatingUiReady = false
    private var detectedDisplayCornerRatio: Float? = null

    val isFloatingKeyboard: Boolean
        get() = floatingController?.isFloating == true

    fun toggleFloatingKeyboard() {
        floatingController?.toggleFloating()
    }

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        updateKeyboardSize()

        floatingDragHandle = imageView {
            setImageResource(R.drawable.ic_baseline_drag_handle_24)
            imageTintList = ColorStateList.valueOf(theme.keyTextColor)
            contentDescription = context.getString(R.string.floating_keyboard_drag)
            isClickable = true
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        floatingResizeHandle = imageView {
            setImageResource(R.drawable.ic_resize_bottom_right)
            imageTintList = ColorStateList.valueOf(theme.keyTextColor)
            contentDescription = context.getString(R.string.floating_keyboard_resize)
            isClickable = true
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        floatingControls = constraintLayout {
            visibility = GONE
            add(floatingDragHandle, lParams(dp(72), matchParent) {
                centerHorizontally()
                topOfParent()
                bottomOfParent()
            })
            add(floatingResizeHandle, lParams(dp(48), matchParent) {
                rightOfParent()
                topOfParent()
                bottomOfParent()
            })
        }
        keyboardPanel = constraintLayout {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = min(view.width, view.height) * floatingCornerRatio()
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            add(preedit.ui.root, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(keyboardView, lParams(matchParent, wrapContent) {
                below(preedit.ui.root)
                centerHorizontally()
            })
            add(floatingControls, lParams(matchParent, dp(FLOATING_CONTROLS_HEIGHT)) {
                below(keyboardView)
                bottomOfParent()
                centerHorizontally()
            })
        }
        preedit.ui.root.addOnLayoutChangeListener {
                _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            floatingController?.onTopOverlayHeightChanged(
                oldHeight = oldBottom - oldTop,
                newHeight = bottom - top
            )
        }
        keyboardPanel.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.invalidateOutline()
        }
        add(keyboardPanel, lParams(matchParent, wrapContent) {
            leftOfParent()
            topOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
        floatingUiReady = true

        val controller = FloatingKeyboardController(
            host = this,
            panel = keyboardPanel,
            controls = floatingControls,
            dragHandle = floatingDragHandle,
            resizeHandle = floatingResizeHandle,
            sessionState = floatingKeyboardSessionState,
            onKeyboardHeightChanged = { updateKeyboardSize() },
            onPanelWidthChanged = { updateKeyboardContentScale() },
            onFloatingChanged = {
                updateKeyboardSize()
                kawaiiBar.updateFloatingKeyboardButton(it)
            },
            requestInsetsUpdate = {
                service.window.window?.decorView?.requestLayout()
            }
        )
        floatingController = controller
        controller.start()
        kawaiiBar.updateFloatingKeyboardButton(controller.isFloating)

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        val floating = isFloatingKeyboard
        val floatingHeight = if (floating) floatingController?.keyboardHeightPx else null
        windowManager.view.updateLayoutParams {
            height = floatingHeight ?: keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = if (floating) 0 else keyboardBottomPaddingPx
        }
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = if (floating) 0 else navBarBottomInset
        }
        val sidePadding = if (floating) 0 else keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
        if (floatingUiReady) {
            updateFloatingPresentation()
        }
    }

    private fun updateFloatingPresentation() {
        val floating = isFloatingKeyboard
        keyboardWindow.setUsePortraitStyle(floating)
        windowManager.setUsePortraitKeyboardStyle(floating)
        kawaiiBar.setUsePortraitKeyboardStyle(floating)
        keyboardPanel.background = if (floating) floatingPanelBackground else null
        keyboardPanel.clipToOutline = floating
        keyboardPanel.invalidateOutline()
        customBackground.visibility = if (floating) GONE else VISIBLE
        updateKeyboardContentScale()
    }

    private fun updateKeyboardContentScale() {
        if (!isFloatingKeyboard) {
            keyboardWindow.setContentScale(1f)
            windowManager.setContentScale(1f)
            kawaiiBar.setContentScale(1f, 1f)
            preedit.ui.setContentScale(1f)
            popup.setContentScale(1f)
            return
        }
        val panelWidth = keyboardPanel.layoutParams.width.takeIf { it > 0 }
            ?: keyboardPanel.width.takeIf { it > 0 }
            ?: return
        val availableWidth = portraitKeyboardContentWidthPx
        val baseKeyboardHeight = portraitKeyboardHeightPx
        val currentKeyboardHeight =
            (floatingController?.keyboardHeightPx ?: baseKeyboardHeight).coerceAtLeast(1)
        val widthScale = panelWidth.toFloat() / availableWidth
        val heightScale = currentKeyboardHeight.toFloat() / baseKeyboardHeight
        val contentScale = min(widthScale, heightScale).coerceIn(MIN_KEY_CONTENT_SCALE, 1f)
        val toolbarScale = widthScale.coerceIn(MIN_TOOLBAR_CONTENT_SCALE, 1f)
        val barTextScale = MIN_BAR_TEXT_SCALE +
            (1f - MIN_BAR_TEXT_SCALE) * widthScale.coerceIn(0f, 1f)
        keyboardWindow.setContentScale(
            contentScale,
            widthScale.coerceIn(0f, 1f),
            heightScale.coerceIn(0f, 1f)
        )
        windowManager.setContentScale(contentScale, toolbarScale)
        kawaiiBar.setContentScale(toolbarScale, barTextScale, contentScale)
        preedit.ui.setContentScale(contentScale)
        popup.setContentScale(contentScale)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        navBarBottomInset = getNavBarBottomInset(insets)
        updateDetectedDisplayCornerRatio(insets)
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = if (isFloatingKeyboard) 0 else navBarBottomInset
        }
        floatingController?.updateWindowInsets(insets)
        return insets
    }

    private fun floatingCornerRatio(): Float {
        val configured = ThemeManager.prefs.floatingKeyboardCornerRadius.getValue()
        return if (configured > 0) configured / 100f
        else detectedDisplayCornerRatio ?: DEFAULT_FLOATING_CORNER_RATIO
    }

    private fun updateDetectedDisplayCornerRatio(insets: WindowInsets) {
        detectedDisplayCornerRatio =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                detectDisplayCornerRatio(insets)
            } else {
                null
            }
        if (floatingUiReady) keyboardPanel.invalidateOutline()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun detectDisplayCornerRatio(insets: WindowInsets): Float? {
        val displayShortEdge = min(width, height)
        if (displayShortEdge <= 0) return null
        val radius = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT
        ).maxOfOrNull { insets.getRoundedCorner(it)?.radius ?: 0 } ?: 0
        return radius.takeIf { it > 0 }?.toFloat()?.div(displayShortEdge)
    }

    fun getFloatingKeyboardBoundsInWindow(outBounds: Rect): Boolean {
        if (!isFloatingKeyboard || keyboardPanel.width <= 0 || keyboardPanel.height <= 0) {
            outBounds.setEmpty()
            return false
        }
        keyboardPanel.getLocationInWindow(floatingKeyboardLocation)
        val left = floatingKeyboardLocation[0]
        val top = floatingKeyboardLocation[1]
        outBounds.set(left, top, left + keyboardPanel.width, top + keyboardPanel.height)
        return true
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        floatingController?.destroy()
        floatingController = null
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FLOATING_CONTROLS_HEIGHT = 32
        const val MIN_KEY_CONTENT_SCALE = 0.5f
        const val MIN_TOOLBAR_CONTENT_SCALE = 0.9f
        const val MIN_BAR_TEXT_SCALE = 0.8f
        const val DEFAULT_FLOATING_CORNER_RATIO = 0.06f
    }

}
