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
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.CommaKey
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LanguageKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
import org.fcitx.fcitx5.android.input.keyboard.SymbolKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
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

    private class PickerSymbolKey : KeyDef(
        Appearance.Text(
            displayText = "?123",
            textSize = 18f,
            percentWidth = 0.13f,
            variant = Appearance.Variant.Alternative,
        ),
        setOf(
            Behavior.Press(KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol))
        ),
    )

    private companion object {
        val Layout = listOf(
            listOf(
                PickerSymbolKey(),
                CommaKey(
                    percentWidth = 0.09f,
                    variant = KeyDef.Appearance.Variant.Alternative,
                ),
                LanguageKey(),
                SpaceKey(),
                SymbolKey(
                    symbol = ".",
                    percentWidth = 0.09f,
                    variant = KeyDef.Appearance.Variant.Alternative,
                ),
                ReturnKey(percentWidth = 0.13f),
                BackspaceKey(percentWidth = 0.13f),
            )
        )
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
