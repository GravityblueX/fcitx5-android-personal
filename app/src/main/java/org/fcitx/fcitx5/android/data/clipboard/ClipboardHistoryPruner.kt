/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry

internal object ClipboardHistoryPruner {

    fun entryIdsToDelete(entries: List<ClipboardEntry>, limit: Int): IntArray {
        val countToDelete = (entries.size - limit.coerceAtLeast(0)).coerceAtLeast(0)
        return entries
            .asSequence()
            .sortedWith(compareBy<ClipboardEntry> { it.timestamp }.thenBy { it.id })
            .take(countToDelete)
            .map { it.id }
            .toList()
            .toIntArray()
    }
}
