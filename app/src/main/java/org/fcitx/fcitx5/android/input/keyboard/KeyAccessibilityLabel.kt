/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R

internal sealed interface KeyAccessibilityLabel {
    data class Text(val text: String) : KeyAccessibilityLabel
    data class Resource(@StringRes val resId: Int) : KeyAccessibilityLabel
}

internal fun returnKeyAccessibilityLabel(@DrawableRes drawable: Int): KeyAccessibilityLabel.Resource {
    val resId = when (drawable) {
        R.drawable.ic_baseline_arrow_forward_24 -> R.string.accessibility_go
        R.drawable.ic_baseline_search_24 -> R.string.accessibility_search
        R.drawable.ic_baseline_send_24 -> R.string.accessibility_send
        R.drawable.ic_baseline_keyboard_tab_24 -> R.string.accessibility_next
        R.drawable.ic_baseline_done_24 -> R.string.accessibility_done
        R.drawable.ic_baseline_keyboard_tab_reverse_24 -> R.string.accessibility_previous
        else -> R.string.accessibility_enter
    }
    return KeyAccessibilityLabel.Resource(resId)
}


internal fun keyAccessibilityLabel(def: KeyDef): KeyAccessibilityLabel {
    return when (def) {
        is CapsKey -> KeyAccessibilityLabel.Resource(R.string.accessibility_shift)
        is BackspaceKey -> KeyAccessibilityLabel.Resource(R.string.backspace)
        is QuickPhraseKey -> KeyAccessibilityLabel.Resource(R.string.accessibility_quick_phrase)
        is LanguageKey -> KeyAccessibilityLabel.Resource(R.string.choose_input_method)
        is SpaceKey,
        is MiniSpaceKey -> KeyAccessibilityLabel.Resource(R.string.accessibility_space)
        is ReturnKey -> KeyAccessibilityLabel.Resource(R.string.accessibility_enter)
        is ImageLayoutSwitchKey ->
            KeyAccessibilityLabel.Resource(R.string.accessibility_switch_keyboard_layout)
        is ImagePickerSwitchKey -> KeyAccessibilityLabel.Resource(R.string.emoji_and_symbols)
        else -> when (val appearance = def.appearance) {
            is KeyDef.Appearance.Text -> KeyAccessibilityLabel.Text(appearance.displayText)
            is KeyDef.Appearance.Image -> KeyAccessibilityLabel.Resource(R.string.accessibility_keyboard_key)
        }
    }
}
