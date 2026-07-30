/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.LanguageKey
import org.fcitx.fcitx5.android.input.keyboard.ReturnKey
import org.fcitx.fcitx5.android.input.keyboard.SpaceKey
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

    private class SymbolKey : KeyDef(
        Appearance.Text(
            displayText = "?123",
            textSize = 18f,
            percentWidth = 0.14f,
            variant = Appearance.Variant.Alternative,
        ),
        setOf(
            Behavior.Press(KeyAction.PickerSwitchAction(PickerWindow.Key.Symbol))
        ),
    )

    private class EmojiKey : KeyDef(
        Appearance.Image(
            src = R.drawable.ic_baseline_tag_faces_24,
            percentWidth = 0.11f,
            variant = Appearance.Variant.Alternative,
        ),
        setOf(
            Behavior.Press(KeyAction.PickerSwitchAction(PickerWindow.Key.Emoji))
        ),
    )

    private companion object {
        val Layout = listOf(
            listOf(
                SymbolKey(),
                EmojiKey(),
                LanguageKey(),
                SpaceKey(),
                BackspaceKey(percentWidth = 0.14f),
                ReturnKey(percentWidth = 0.14f),
            )
        )
    }

    private val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    private val returnKey: ImageKeyView by lazy { findViewById(R.id.button_return) }

    init {
        space.mainText.text = context.getString(R.string.handwriting)
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        space.mainText.text = context.getString(R.string.handwriting)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        returnKey.img.imageResource = returnDrawable
    }
}
