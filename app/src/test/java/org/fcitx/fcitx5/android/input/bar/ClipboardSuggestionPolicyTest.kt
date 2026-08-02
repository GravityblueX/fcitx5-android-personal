/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSuggestionPolicyTest {

    @Test
    fun allowsSuggestionsOnlyForAnActiveNonPasswordInput() {
        assertTrue(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = true,
                isPasswordField = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = false,
                isPasswordField = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = true,
                isPasswordField = true,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = false,
                hasActiveInput = true,
                isPasswordField = false,
            )
        )
    }
}
