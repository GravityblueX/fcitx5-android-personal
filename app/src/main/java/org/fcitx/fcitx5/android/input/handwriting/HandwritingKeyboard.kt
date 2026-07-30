/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

/**
 * Compact Gboard-inspired action row shown below the handwriting canvas.
 * Full-screen handwriting is intentionally omitted until that mode exists.
 */
@SuppressLint("ViewConstructor")
class HandwritingKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val RETURN_KEY_WIDTH_FRACTION = 0.15f

        private val Layout = listOf(TextKeyboard.BottomRow)
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val returnKey: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val punctuationKeys by lazy {
        allViews
            .filterIsInstance<TextKeyView>()
            .filter {
                (it.def as? KeyDef.Appearance.Text)?.displayText == "," ||
                        (it.def as? KeyDef.Appearance.Text)?.displayText == "."
            }
            .toList()
    }
    init {
        onRecognitionModeUpdate(HandwritingRecognitionMode.Chinese)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        returnKey.img.imageResource = returnDrawable
    }

    protected override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        val transformed =
            if (action is KeyAction.LayoutSwitchAction) {
                KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol)
            } else {
                action
            }
        super.onAction(transformed, source)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationKeys.forEach {
            val displayText = (it.def as KeyDef.Appearance.Text).displayText
            it.mainText.text = mapping.getOrDefault(displayText, displayText)
        }
    }

    fun onRecognitionModeUpdate(mode: HandwritingRecognitionMode) {
        space.mainText.text = context.getString(mode.stringRes)
    }
}
