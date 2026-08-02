/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class TextKeyboardLayoutSwitchTest {

    @Test
    fun longPressLayoutMenuOffersNumberAndSymbolLayouts() {
        val switchKey = TextKeyboard.BottomRow.first() as LayoutSwitchKey
        val menu = switchKey.popup.orEmpty().filterIsInstance<KeyDef.Popup.Menu>().single()
        val actions = menu.items.mapNotNull {
            (it.action as? KeyAction.LayoutSwitchAction)?.act
        }

        assertEquals(listOf(NumberKeyboard.Name, PickerWindow.Key.Symbol.name), actions)
    }
}
