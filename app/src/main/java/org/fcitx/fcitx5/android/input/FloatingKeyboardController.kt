/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.annotation.Keep
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.FloatingKeyboardMode
import splitties.dimensions.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manual floating-mode choices belong to the current IME service session. Keeping them outside
 * [FloatingKeyboardController] lets an InputView replacement retain the choice without changing
 * the user's automatic floating-mode preference.
 */
internal class FloatingKeyboardSessionState {
    private var sharedFloatingOverride: Boolean? = null
    private var portraitFloatingOverride: Boolean? = null
    private var landscapeFloatingOverride: Boolean? = null
    private var overrideMode: FloatingKeyboardMode? = null

    fun setManualOverride(value: Boolean, mode: FloatingKeyboardMode, orientation: Int) {
        if (overrideMode != null && overrideMode != mode) {
            clearManualOverride()
        }
        overrideMode = mode
        if (mode == FloatingKeyboardMode.Landscape) {
            when (orientation) {
                Configuration.ORIENTATION_PORTRAIT -> portraitFloatingOverride = value
                Configuration.ORIENTATION_LANDSCAPE -> landscapeFloatingOverride = value
                else -> sharedFloatingOverride = value
            }
        } else {
            sharedFloatingOverride = value
        }
    }

    fun clearManualOverride() {
        sharedFloatingOverride = null
        portraitFloatingOverride = null
        landscapeFloatingOverride = null
        overrideMode = null
    }

    fun manualOverrideFor(mode: FloatingKeyboardMode, orientation: Int): Boolean? {
        if (overrideMode != null && overrideMode != mode) {
            clearManualOverride()
        }
        return if (mode == FloatingKeyboardMode.Landscape) {
            // Landscape automatic mode needs an independent manual choice for each direction.
            // An override made while portrait must be restored after a landscape round-trip,
            // while an untouched landscape direction must still follow the automatic policy.
            when (orientation) {
                Configuration.ORIENTATION_PORTRAIT -> portraitFloatingOverride
                Configuration.ORIENTATION_LANDSCAPE -> landscapeFloatingOverride
                else -> sharedFloatingOverride
            }
        } else {
            // Disabled and Always have no orientation-dependent policy, so a manual choice
            // applies to both directions for the rest of the IME service session.
            sharedFloatingOverride
        }
    }
}

/**
 * Fits a freely resized keyboard into a new safe area with one uniform scale factor. This retains
 * the user's chosen aspect ratio when a rotation makes either axis too small.
 */
internal fun fitFloatingKeyboardSize(
    desiredWidth: Int,
    desiredHeight: Int,
    minWidth: Int,
    maxWidth: Int,
    minHeight: Int,
    maxHeight: Int
): Pair<Int, Int> {
    val width = desiredWidth.coerceAtLeast(1)
    val height = desiredHeight.coerceAtLeast(1)
    val lowerScale = max(
        minWidth / width.toFloat(),
        minHeight / height.toFloat()
    )
    val upperScale = min(
        maxWidth / width.toFloat(),
        maxHeight / height.toFloat()
    )
    val scale = when {
        lowerScale <= 1f && upperScale >= 1f -> 1f
        lowerScale > 1f && lowerScale <= upperScale -> lowerScale
        upperScale < 1f && upperScale >= lowerScale -> upperScale
        else -> upperScale.coerceIn(0f, 1f)
    }
    return (
        (width * scale).roundToInt().coerceIn(minWidth, maxWidth)
        ) to (
        (height * scale).roundToInt().coerceIn(minHeight, maxHeight)
        )
}

/**
 * Positions [panel] inside the full-screen IME window. Position is stored relative to the current
 * safe area, while size is stored in orientation-independent dp units.
 */
internal class FloatingKeyboardController(
    private val host: ViewGroup,
    private val panel: View,
    private val controls: View,
    private val dragHandle: View,
    private val resizeHandle: View,
    private val sessionState: FloatingKeyboardSessionState,
    private val onKeyboardHeightChanged: (Int?) -> Unit,
    private val onPanelWidthChanged: () -> Unit,
    private val onFloatingChanged: (Boolean) -> Unit,
    private val requestInsetsUpdate: () -> Unit,
) {
    private val prefs = AppPrefs.getInstance()
    private val mode = prefs.keyboard.floatingKeyboardMode
    private val positionX = prefs.internal.floatingKeyboardX
    private val positionY = prefs.internal.floatingKeyboardY
    private val legacyWidth = prefs.internal.floatingKeyboardWidth
    private val legacyHeight = prefs.internal.floatingKeyboardHeight
    private val sizeCustomized = prefs.internal.floatingKeyboardSizeCustomized
    private val storedWidthDp = prefs.internal.floatingKeyboardWidthDp
    private val storedHeightDp = prefs.internal.floatingKeyboardHeightDp
    private val sizeFormat = prefs.internal.floatingKeyboardSizeFormat
    private val portraitKeyboardHeight = prefs.keyboard.keyboardHeightPercent
    private val portraitKeyboardSidePadding = prefs.keyboard.keyboardSidePadding

    /**
     * Only an explicit resize makes the stored width and height authoritative. Older builds wrote
     * size values after a move gesture as well, which made a portrait-derived default turn into
     * landscape safe-area proportions on the next cold start.
     */
    private val hasStoredSize: Boolean
        get() =
            sizeCustomized.getValue() &&
                sizeFormat.getValue() >= SIZE_FORMAT_DP &&
                storedWidthDp.sharedPreferences.contains(storedWidthDp.key) &&
                storedHeightDp.sharedPreferences.contains(storedHeightDp.key) &&
                storedWidthDp.getValue().isFinite() &&
                storedWidthDp.getValue() > 0f &&
                storedHeightDp.getValue().isFinite() &&
                storedHeightDp.getValue() > 0f

    private val hasLegacyStoredSize: Boolean
        get() =
            sizeCustomized.getValue() &&
                sizeFormat.getValue() < SIZE_FORMAT_DP &&
                legacyWidth.sharedPreferences.contains(legacyWidth.key) &&
                legacyHeight.sharedPreferences.contains(legacyHeight.key)

    var isFloating: Boolean = false
        private set

    var keyboardHeightPx: Int? = null
        private set

    private var safeLeft = 0
    private var safeTop = 0
    private var safeRightInset = 0
    private var safeBottomInset = 0
    private var topOverlayHeight = 0
    private var pendingStoredPosition = false
    private var gesture = Gesture.None

    private var gestureRawX = 0f
    private var gestureRawY = 0f
    private var gesturePanelX = 0f
    private var gesturePanelY = 0f
    private var gesturePanelWidth = 0
    private var gestureKeyboardHeight = 0

    private enum class Gesture {
        None,
        Drag,
        Resize,
    }

    @Keep
    private val modeChangeListener =
        ManagedPreference.OnChangeListener<Any> { _, _ ->
            sessionState.clearManualOverride()
            updateMode()
        }

    private val hostLayoutListener =
        View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (isFloating) {
                    applyStoredGeometry()
                } else {
                    dockPanel()
                }
            }
        }

    private val panelLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isFloating) {
                dockPanel()
                return@OnLayoutChangeListener
            }
            keepPanelInsideSafeArea()
            if (pendingStoredPosition && panel.width > 0 && panel.height > 0) {
                pendingStoredPosition = false
                positionPanelFromPreferences()
            }
        }

    fun start() {
        mode.registerOnChangeListener(modeChangeListener)
        host.addOnLayoutChangeListener(hostLayoutListener)
        panel.addOnLayoutChangeListener(panelLayoutListener)
        installGestureListeners()
        updateMode()
    }

    fun destroy() {
        mode.unregisterOnChangeListener(modeChangeListener)
        host.removeOnLayoutChangeListener(hostLayoutListener)
        panel.removeOnLayoutChangeListener(panelLayoutListener)
        dragHandle.setOnTouchListener(null)
        resizeHandle.setOnTouchListener(null)
    }

    fun updateWindowInsets(insets: WindowInsets) {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets)
        val system = compat.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = compat.getInsets(WindowInsetsCompat.Type.displayCutout())
        val mandatory = compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        // Side gesture insets are intentionally not used here. The left edge is the physical
        // display edge; the right edge still excludes a real system bar/cutout so the panel
        // cannot drift outside the visible area on landscape navigation-bar layouts.
        val newLeft = 0
        val newTop = max(system.top, cutout.top)
        val newRight = max(system.right, cutout.right)
        val newBottom = max(max(system.bottom, mandatory.bottom), cutout.bottom)
        if (
            newLeft == safeLeft &&
            newTop == safeTop &&
            newRight == safeRightInset &&
            newBottom == safeBottomInset
        ) {
            return
        }
        safeLeft = newLeft
        safeTop = newTop
        safeRightInset = newRight
        safeBottomInset = newBottom
        if (isFloating && gesture == Gesture.None) {
            applyStoredGeometry()
        }
    }

    fun toggleFloating() {
        sessionState.setManualOverride(
            !isFloating,
            mode.getValue(),
            host.resources.configuration.orientation
        )
        updateMode()
    }

    private fun updateMode() {
        val currentMode = mode.getValue()
        val configuration = host.resources.configuration
        val floating = sessionState.manualOverrideFor(currentMode, configuration.orientation)
            ?: currentMode.isEnabled(host.resources.configuration)
        if (floating == isFloating) {
            if (floating) applyStoredGeometry() else dockPanel()
            return
        }
        isFloating = floating
        controls.visibility = if (floating) View.VISIBLE else View.GONE
        panel.elevation = if (floating) host.context.dp(8).toFloat() else 0f
        keyboardHeightPx = null
        onFloatingChanged(floating)
        if (floating) {
            applyStoredGeometry()
        } else {
            dockPanel()
        }
        requestInsetsUpdate()
    }

    fun onTopOverlayHeightChanged(oldHeight: Int, newHeight: Int) {
        topOverlayHeight = newHeight.coerceAtLeast(0)
        if (!isFloating || pendingStoredPosition || oldHeight == newHeight) return
        // The preedit/IME hint sits above the keyboard. Keep the keyboard's top edge stable
        // while that area appears or disappears, so the keys are never pushed downward.
        panel.translationY -= (newHeight - oldHeight)
        keepPanelInsideSafeArea()
    }

    private val safeWidth: Int
        get() = (host.width - safeLeft - safeRightInset).coerceAtLeast(0)

    private val safeHeight: Int
        get() = (host.height - safeTop - safeBottomInset).coerceAtLeast(0)

    private val minPanelX: Int
        get() = safeLeft - panel.left

    private val maxPanelX: Int
        get() = (safeLeft + safeWidth - panel.width - panel.left).coerceAtLeast(minPanelX)

    private val minPanelY: Int
        get() = safeTop - topOverlayHeight - panel.top

    private val maxPanelY: Int
        get() = (safeTop + safeHeight - panel.height - panel.top).coerceAtLeast(minPanelY)

    private fun applyStoredGeometry() {
        if (!isFloating || safeWidth <= 0 || safeHeight <= 0) return
        val minWidth = min(host.context.dp(MIN_WIDTH_DP), safeWidth)
        val maxKeyboardHeight = maxKeyboardHeight()
        val minKeyboardHeight = min(host.context.dp(MIN_KEYBOARD_HEIGHT_DP), maxKeyboardHeight)

        val desiredStoredSize = when {
            hasStoredSize -> {
                val density = host.resources.displayMetrics.density.coerceAtLeast(0.1f)
                (storedWidthDp.getValue() * density).roundToInt() to
                    (storedHeightDp.getValue() * density).roundToInt()
            }
            hasLegacyStoredSize -> {
                // Migrate the size currently visible to the user. Subsequent rotations use dp,
                // rather than interpreting these legacy values against a differently shaped area.
                val legacySize =
                    (safeWidth * legacyWidth.getValue().sanitize(DEFAULT_WIDTH_RATIO))
                        .roundToInt() to
                        (safeHeight * legacyHeight.getValue().sanitize(DEFAULT_HEIGHT_RATIO))
                            .roundToInt()
                persistSize(legacySize.first, legacySize.second)
                legacySize
            }
            else -> null
        }

        if (desiredStoredSize != null) {
            val (panelWidth, keyboardHeight) = fitFloatingKeyboardSize(
                desiredStoredSize.first,
                desiredStoredSize.second,
                minWidth,
                safeWidth,
                minKeyboardHeight,
                maxKeyboardHeight
            )
            updatePanelWidth(panelWidth)
            updateKeyboardHeight(keyboardHeight)
        } else {
            val (panelWidth, keyboardHeight) = calculateDefaultSize(
                minWidth,
                safeWidth,
                minKeyboardHeight,
                maxKeyboardHeight
            )
            updatePanelWidth(panelWidth)
            updateKeyboardHeight(keyboardHeight)
        }

        pendingStoredPosition = true
        panel.requestLayout()
    }

    /**
     * Scale the portrait docked keyboard uniformly so its key-area aspect ratio is retained.
     * The initial scale sits exactly halfway between the smallest and largest scale that fits
     * both the width and height constraints.
     */
    private fun calculateDefaultSize(
        minWidth: Int,
        maxWidth: Int,
        minKeyboardHeight: Int,
        maxKeyboardHeight: Int
    ): Pair<Int, Int> {
        val metrics = host.resources.displayMetrics
        val portraitDisplayWidth = min(metrics.widthPixels, metrics.heightPixels)
        val portraitDisplayHeight = max(metrics.widthPixels, metrics.heightPixels)
        val portraitContentWidth = (
            portraitDisplayWidth - 2 * host.context.dp(portraitKeyboardSidePadding.getValue())
            ).coerceAtLeast(1)
        val portraitContentHeight = (
            portraitDisplayHeight * portraitKeyboardHeight.getValue() / 100f
            ).coerceAtLeast(1f)

        val minScale = max(
            minWidth / portraitContentWidth.toFloat(),
            minKeyboardHeight / portraitContentHeight
        )
        val maxScale = min(
            1f,
            min(
                maxWidth / portraitContentWidth.toFloat(),
                maxKeyboardHeight / portraitContentHeight
            )
        )
        if (maxScale < minScale) {
            // This can only happen in an unusually constrained window. Fill the limiting axis
            // and retain as much of the portrait proportion as the hard minimums allow.
            val width = maxWidth.coerceAtLeast(minWidth)
            val keyboardHeight = (width / portraitContentWidth.toFloat() * portraitContentHeight)
                .roundToInt()
                .coerceIn(minKeyboardHeight, maxKeyboardHeight)
            return width to keyboardHeight
        }
        val initialScale = (minScale + maxScale) / 2f
        return (
            (portraitContentWidth * initialScale).roundToInt().coerceIn(minWidth, maxWidth)
            ) to (
            (portraitContentHeight * initialScale).roundToInt()
                .coerceIn(minKeyboardHeight, maxKeyboardHeight)
            )
    }

    private fun dockPanel() {
        if (panel.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            updatePanelWidth(ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (keyboardHeightPx != null) {
            keyboardHeightPx = null
            onKeyboardHeightChanged(null)
        }
        panel.translationX = 0f
        panel.translationY = (host.height - panel.height).coerceAtLeast(0).toFloat()
        requestInsetsUpdate()
    }

    private fun positionPanelFromPreferences() {
        panel.translationX = interpolate(
            minPanelX,
            maxPanelX,
            positionX.getValue().sanitize(DEFAULT_X_RATIO)
        )
        panel.translationY = interpolate(
            minPanelY,
            maxPanelY,
            positionY.getValue().sanitize(DEFAULT_Y_RATIO)
        )
        requestInsetsUpdate()
    }

    private fun keepPanelInsideSafeArea() {
        if (!isFloating || safeWidth <= 0 || safeHeight <= 0) return
        val overflow = panel.height - topOverlayHeight - safeHeight
        val currentHeight = keyboardHeightPx
        if (overflow > 0 && currentHeight != null) {
            val maxKeyboardHeight = maxKeyboardHeight()
            val minKeyboardHeight = min(host.context.dp(MIN_KEYBOARD_HEIGHT_DP), maxKeyboardHeight)
            val adjusted = (currentHeight - overflow).coerceIn(
                minKeyboardHeight,
                maxKeyboardHeight
            )
            if (adjusted != currentHeight) {
                val currentWidth =
                    panel.layoutParams.width.takeIf { it > 0 } ?: panel.width
                val minWidth = min(host.context.dp(MIN_WIDTH_DP), safeWidth)
                val adjustedWidth = (
                    currentWidth * (adjusted / currentHeight.toFloat())
                    ).roundToInt().coerceIn(minWidth, safeWidth)
                updatePanelWidth(adjustedWidth)
                updateKeyboardHeight(adjusted)
                return
            }
        }
        panel.translationX = panel.translationX.coerceIn(
            minPanelX.toFloat(),
            maxPanelX.toFloat()
        )
        panel.translationY = panel.translationY.coerceIn(
            minPanelY.toFloat(),
            maxPanelY.toFloat()
        )
        requestInsetsUpdate()
    }

    private fun maxKeyboardHeight(): Int {
        val measuredOverhead = keyboardHeightPx?.let {
            (panel.height - it - topOverlayHeight).coerceAtLeast(0)
        } ?: 0
        val estimatedOverhead = host.context.dp(ESTIMATED_OVERHEAD_DP)
        return (safeHeight - max(measuredOverhead, estimatedOverhead)).coerceAtLeast(1)
    }

    private fun updatePanelWidth(value: Int) {
        val params = panel.layoutParams
        if (params.width == value) return
        params.width = value
        panel.layoutParams = params
        onPanelWidthChanged()
    }

    private fun updateKeyboardHeight(value: Int) {
        if (keyboardHeightPx == value) return
        keyboardHeightPx = value
        onKeyboardHeightChanged(value)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installGestureListeners() {
        dragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(Gesture.Drag, event)
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (gesture != Gesture.Drag) return@setOnTouchListener false
                    val dx = event.rawX - gestureRawX
                    val dy = event.rawY - gestureRawY
                    panel.translationX = gesturePanelX + dx
                    panel.translationY = gesturePanelY + dy
                    keepPanelInsideSafeArea()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gesture == Gesture.Drag) finishGesture(view, event)
                    true
                }

                else -> false
            }
        }
        resizeHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(Gesture.Resize, event)
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (gesture != Gesture.Resize) return@setOnTouchListener false
                    resizeFrom(event)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gesture == Gesture.Resize) finishGesture(view, event)
                    true
                }

                else -> false
            }
        }
    }

    private fun beginGesture(type: Gesture, event: MotionEvent) {
        gesture = type
        gestureRawX = event.rawX
        gestureRawY = event.rawY
        gesturePanelX = panel.translationX
        gesturePanelY = panel.translationY
        gesturePanelWidth = panel.width
        gestureKeyboardHeight = keyboardHeightPx ?: 0
    }

    private fun resizeFrom(event: MotionEvent) {
        val minWidth = min(host.context.dp(MIN_WIDTH_DP), safeWidth)
        val newWidth = (gesturePanelWidth + event.rawX - gestureRawX)
            .roundToInt()
            .coerceIn(minWidth, safeWidth)
        updatePanelWidth(newWidth)

        val maxKeyboardHeight = maxKeyboardHeight()
        val minKeyboardHeight = min(host.context.dp(MIN_KEYBOARD_HEIGHT_DP), maxKeyboardHeight)
        val newHeight = (gestureKeyboardHeight + event.rawY - gestureRawY)
            .roundToInt()
            .coerceIn(minKeyboardHeight, maxKeyboardHeight)
        updateKeyboardHeight(newHeight)
        keepPanelInsideSafeArea()
    }

    private fun finishGesture(view: View, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
        view.parent.requestDisallowInterceptTouchEvent(false)
        val completedGesture = gesture
        gesture = Gesture.None
        keepPanelInsideSafeArea()
        persistGeometry(saveSize = completedGesture == Gesture.Resize)
    }

    private fun persistGeometry(saveSize: Boolean) {
        if (!isFloating || safeWidth <= 0 || safeHeight <= 0) return
        if (saveSize) {
            val currentWidth = panel.layoutParams.width.takeIf { it > 0 } ?: panel.width
            keyboardHeightPx?.let {
                // Keep legacy ratios for downgrade/import compatibility, but the current format
                // always restores from the orientation-independent dp values.
                legacyWidth.setValue((currentWidth.toFloat() / safeWidth).coerceIn(0f, 1f))
                legacyHeight.setValue((it.toFloat() / safeHeight).coerceIn(0f, 1f))
                persistSize(currentWidth, it)
            }
        }
        val travelX = maxPanelX - minPanelX
        val travelY = maxPanelY - minPanelY
        positionX.setValue(
            if (travelX <= 0) 0f
            else ((panel.translationX - minPanelX) / travelX).coerceIn(0f, 1f)
        )
        positionY.setValue(
            if (travelY <= 0) 0f
            else ((panel.translationY - minPanelY) / travelY).coerceIn(0f, 1f)
        )
    }

    private fun persistSize(panelWidthPx: Int, keyboardHeightPx: Int) {
        val density = host.resources.displayMetrics.density.coerceAtLeast(0.1f)
        storedWidthDp.setValue(panelWidthPx / density)
        storedHeightDp.setValue(keyboardHeightPx / density)
        sizeFormat.setValue(SIZE_FORMAT_DP)
        sizeCustomized.setValue(true)
    }

    private fun interpolate(start: Int, end: Int, ratio: Float): Float =
        start + (end - start) * ratio.coerceIn(0f, 1f)

    private fun Float.sanitize(defaultValue: Float): Float =
        if (isFinite()) coerceIn(0f, 1f) else defaultValue

    companion object {
        private const val MIN_WIDTH_DP = 280
        private const val MIN_KEYBOARD_HEIGHT_DP = 128
        private const val ESTIMATED_OVERHEAD_DP = 72
        private const val DEFAULT_X_RATIO = 0.5f
        private const val DEFAULT_Y_RATIO = 0.85f
        private const val DEFAULT_WIDTH_RATIO = 0.65f
        private const val DEFAULT_HEIGHT_RATIO = 0.48f
        private const val SIZE_FORMAT_DP = 1
    }
}
