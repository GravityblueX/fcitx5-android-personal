/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

internal object PairedPunctuationDeletionPolicy {
    private val pairs = setOf(
        "()", "[]", "{}", "（）", "【】", "「」", "『』", "《》", "“”", "‘’", "\"\"", "''",
    )

    fun shouldDeletePair(beforeCursor: Char?, afterCursor: Char?): Boolean =
        beforeCursor != null && afterCursor != null && "$beforeCursor$afterCursor" in pairs
}
