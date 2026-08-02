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
    fun allowsHistoryOnlyForAnActiveNonSensitiveUnlockedInput() {
        assertTrue(
            ClipboardSuggestionPolicy.canOpenHistory(
                hasActiveInput = true,
                isSensitiveField = false,
                isDeviceLocked = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canOpenHistory(
                hasActiveInput = false,
                isSensitiveField = false,
                isDeviceLocked = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canOpenHistory(
                hasActiveInput = true,
                isSensitiveField = true,
                isDeviceLocked = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canOpenHistory(
                hasActiveInput = true,
                isSensitiveField = false,
                isDeviceLocked = true,
            )
        )
    }

    @Test
    fun allowsSuggestionsOnlyForAnActiveNonSensitiveUnlockedInput() {
        assertTrue(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = true,
                isSensitiveField = false,
                isDeviceLocked = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = false,
                isSensitiveField = false,
                isDeviceLocked = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = true,
                isSensitiveField = true,
                isDeviceLocked = false,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = true,
                hasActiveInput = true,
                isSensitiveField = false,
                isDeviceLocked = true,
            )
        )
        assertFalse(
            ClipboardSuggestionPolicy.canDisplay(
                suggestionsEnabled = false,
                hasActiveInput = true,
                isSensitiveField = false,
                isDeviceLocked = false,
            )
        )
    }
}
