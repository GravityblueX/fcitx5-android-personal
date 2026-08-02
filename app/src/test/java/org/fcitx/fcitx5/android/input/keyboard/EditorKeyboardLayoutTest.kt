/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorKeyboardLayoutTest {

    @Test
    fun emailEditorsUseEmailPunctuation() {
        assertEquals(
            TextKeyboard.EmailName,
            keyboardLayoutForInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
        )
        assertEquals(
            TextKeyboard.EmailName,
            keyboardLayoutForInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        )
    }

    @Test
    fun uriEditorsUseUrlPunctuation() {
        assertEquals(
            TextKeyboard.UrlName,
            keyboardLayoutForInputType(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
        )
    }

    @Test
    fun numericAndOrdinaryEditorsKeepTheirExistingLayouts() {
        assertEquals(
            NumberKeyboard.Name,
            keyboardLayoutForInputType(InputType.TYPE_CLASS_NUMBER)
        )
        assertEquals(
            NumberKeyboard.Name,
            keyboardLayoutForInputType(InputType.TYPE_CLASS_PHONE)
        )
        assertEquals(
            TextKeyboard.Name,
            keyboardLayoutForInputType(InputType.TYPE_CLASS_TEXT)
        )
    }
}
