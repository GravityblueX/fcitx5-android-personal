/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import splitties.dimensions.dp
import timber.log.Timber

internal fun shouldCollapseNumberRow(
    startX: Float,
    currentX: Float,
    threshold: Float,
    leftToRight: Boolean,
): Boolean {
    val distance = if (leftToRight) currentX - startX else startX - currentX
    return distance > threshold
}

@SuppressLint("ViewConstructor")
class NumberRow(ctx: Context, theme: Theme) : BaseKeyboard(ctx, theme, Layout) {

    private var gesturePointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureStartX = 0f
    private var collapseGestureTriggered: Boolean = false

    var onCollapseListener: (() -> Unit)? = null

    private fun startGesture(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        gesturePointerId = event.getPointerId(pointerIndex)
        gestureStartX = event.getX(pointerIndex)
        collapseGestureTriggered = false
    }

    private fun checkGesture(ev: MotionEvent): Boolean {
        val pointerIndex = ev.findPointerIndex(gesturePointerId)
        if (pointerIndex < 0) return false
        val shouldCollapse = shouldCollapseNumberRow(
            startX = gestureStartX,
            currentX = ev.getX(pointerIndex),
            threshold = dp(KawaiiBarComponent.HEIGHT).toFloat(),
            leftToRight = context.resources.configuration.layoutDirection == LAYOUT_DIRECTION_LTR,
        )
        if (shouldCollapse) {
            Timber.d("NumberRow: intercepted gesture from child keyboard to handle swipe")
            resetState()
            collapseGestureTriggered = true
            return true
        }
        return false
    }

    private fun resetState() {
        gesturePointerId = MotionEvent.INVALID_POINTER_ID
        gestureStartX = 0f
        collapseGestureTriggered = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(ev)
            MotionEvent.ACTION_MOVE -> {
                if (checkGesture(ev)) return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> resetState()
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(event)
            MotionEvent.ACTION_MOVE -> checkGesture(event)
            MotionEvent.ACTION_UP -> {
                val shouldCollapse = collapseGestureTriggered
                resetState()
                if (shouldCollapse) {
                    onCollapseListener?.invoke()
                    handled = true
                }
            }
            MotionEvent.ACTION_CANCEL -> resetState()
        }
        return super.onTouchEvent(event) || handled
    }

    override fun onDetachedFromWindow() {
        resetState()
        super.onDetachedFromWindow()
    }

    companion object {
        val Layout = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { digit ->
                KeyDef(
                    KeyDef.Appearance.Text(
                        displayText = digit,
                        textSize = 21f,
                        border = KeyDef.Appearance.Border.Off,
                        margin = false
                    ),
                    setOf(
                        KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(digit.codePointAt(0))))
                    ),
                    arrayOf(KeyDef.Popup.Preview(digit))
                )
            }
        )
    }
}
