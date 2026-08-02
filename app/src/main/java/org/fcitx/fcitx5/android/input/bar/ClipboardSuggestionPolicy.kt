/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

internal object ClipboardSuggestionPolicy {

    fun canDisplay(
        suggestionsEnabled: Boolean,
        hasActiveInput: Boolean,
        isPasswordField: Boolean,
    ): Boolean = suggestionsEnabled && canOpenHistory(hasActiveInput, isPasswordField)

    fun canOpenHistory(
        hasActiveInput: Boolean,
        isPasswordField: Boolean,
    ): Boolean = hasActiveInput && !isPasswordField
}
