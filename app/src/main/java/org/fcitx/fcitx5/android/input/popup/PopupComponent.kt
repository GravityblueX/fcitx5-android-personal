/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.keyTextScaleForPercent
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList
import kotlin.math.roundToInt

class PopupComponent :
    UniqueComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()
    private val keyTextScale by AppPrefs.getInstance().keyboard.keyTextScale

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissJobs = HashMap<Int, Job>()
    private val dismissGenerations = HashMap<Int, Long>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private var contentScale = 1f

    private val keyBottomMargin: Int
        get() = (context.dp(ThemeManager.prefs.keyVerticalMargin.getValue()) *
            contentScale).roundToInt()
    private val popupWidth: Int
        get() = (context.dp(38) * contentScale).roundToInt()
    private val popupHeight: Int
        get() = (context.dp(116) * contentScale).roundToInt()
    private val popupKeyHeight: Int
        get() = (context.dp(48) * contentScale).roundToInt()
    private val popupRadius: Float
        get() = context.dp(ThemeManager.prefs.keyRadius.getValue()) * contentScale
    private val hideThreshold = 100L

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false
            // Floating keyboard panel has elevation, so the preview layer must stay above it.
            elevation = context.dp(12).toFloat()

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    private fun cancelDismissJob(viewId: Int) {
        dismissJobs.remove(viewId)?.cancel()
        dismissGenerations[viewId] = (dismissGenerations[viewId] ?: 0L) + 1
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        cancelDismissJob(viewId)
        showingEntryUi[viewId]?.apply {
            setTextScale(keyTextScaleForPercent(keyTextScale))
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(
                context,
                theme,
                popupKeyHeight,
                popupRadius,
                contentScale,
                keyTextScaleForPercent(keyTextScale)
            )).apply {
            setTextScale(keyTextScaleForPercent(keyTextScale))
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        popup.root.layoutParams = FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
            // align popup bottom with key border bottom [^1]
            topMargin = bounds.bottom - rootBounds.top - popupHeight - keyBottomMargin
            leftMargin = (bounds.left + bounds.right - popupWidth) / 2 - rootBounds.left
        }
        // make sure that popup.root does not have parent view before adding it under root container
        // it's wired that on some devices it would have a parent view despite it was newly created
        // or just polled from freeEntryUi
        if (popup.root.parent == null) {
            root.addView(popup.root)
        } else if (popup.root.parent !== root) {
            (popup.root.parent as? ViewGroup)?.removeView(popup.root)
            root.addView(popup.root)
        }
        showingEntryUi[viewId] = popup
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        cancelDismissJob(viewId)
        val actions: Array<KeyAction>
        val labels: Array<String>
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = PopupPreset[keyboard.label] ?: return
                actions = Array(preset.size) { KeyAction.FcitxKeyAction(preset[it]) }
                labels = if (keyboard.transformPunctuation && punctuation.enabled) {
                    Array(preset.size) { punctuation.transform(preset[it]) }
                } else preset
            }
            is KeyDef.Popup.Keyboard.Explicit -> {
                actions = Array(keyboard.items.size) { KeyAction.FcitxKeyAction(keyboard.items[it]) }
                labels = keyboard.items
            }
            is KeyDef.Popup.Keyboard.Actions -> {
                actions = Array(keyboard.items.size) { keyboard.items[it].action }
                labels = Array(keyboard.items.size) { keyboard.items[it].label }
            }
        }
        // clear popup preview text         OR create empty popup preview
        showingEntryUi[viewId]?.setText("") ?: showPopup(viewId, "", bounds)
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight + keyBottomMargin,
            contentScale,
            keyTextScaleForPercent(keyTextScale),
            actions,
            labels
        )
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        cancelDismissJob(viewId)
        showingEntryUi[viewId]?.let {
            dismissPopupEntry(viewId, it)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            menu.items,
            contentScale
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
            })
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return showingContainerUi[viewId]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(viewId: Int): KeyAction? {
        return showingContainerUi[viewId]?.onTrigger()
    }

    private fun dismissPopup(viewId: Int) {
        cancelDismissJob(viewId)
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                val generation = dismissGenerations.getValue(viewId)
                dismissJobs[viewId] = service.lifecycleScope.launch {
                    delay(timeLeft)
                    if (dismissGenerations[viewId] != generation) return@launch
                    dismissPopupEntry(viewId, it)
                    dismissJobs.remove(viewId)
                }
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi[viewId]?.also {
            showingContainerUi.remove(viewId)
            root.removeView(it.root)
        }
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        if (showingEntryUi[viewId] !== popup) return
        showingEntryUi.remove(viewId)
        root.removeView(popup.root)
        freeEntryUi.add(popup)
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        dismissGenerations.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        // too too
        showingEntryUi.forEach { (_, entry) ->
            root.removeView(entry.root)
            freeEntryUi.add(entry)
        }
        showingEntryUi.clear()
    }

    fun setContentScale(scale: Float) {
        val scaled = scale.coerceIn(0f, 1f)
        if (contentScale == scaled) return
        contentScale = scaled
        dismissAll()
        freeEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }
}
