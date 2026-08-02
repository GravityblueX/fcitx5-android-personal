/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

internal object ClipboardSuggestionPolicy {

    fun canDisplay(
        suggestionsEnabled: Boolean,
        hasActiveInput: Boolean,
        isSensitiveField: Boolean,
        isDeviceLocked: Boolean,
    ): Boolean = suggestionsEnabled && canOpenHistory(
        hasActiveInput,
        isSensitiveField,
        isDeviceLocked,
    )

    fun canOpenHistory(
        hasActiveInput: Boolean,
        isSensitiveField: Boolean,
        isDeviceLocked: Boolean,
    ): Boolean = hasActiveInput && !isSensitiveField && !isDeviceLocked
}
