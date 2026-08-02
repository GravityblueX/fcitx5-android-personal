/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ClipboardHistoryPrunerTest {

    @Test
    fun removesTheOldestEntriesWhenAllTimestampsMatch() {
        val entries = listOf(
            entry(id = 3, timestamp = 100),
            entry(id = 1, timestamp = 100),
            entry(id = 2, timestamp = 100),
        )

        assertArrayEquals(
            intArrayOf(1),
            ClipboardHistoryPruner.entryIdsToDelete(entries, limit = 2)
        )
    }

    @Test
    fun removesExactlyTheExcessEntriesAcrossTimestampBoundaries() {
        val entries = listOf(
            entry(id = 5, timestamp = 300),
            entry(id = 4, timestamp = 200),
            entry(id = 3, timestamp = 200),
            entry(id = 2, timestamp = 100),
            entry(id = 1, timestamp = 100),
        )

        assertArrayEquals(
            intArrayOf(1, 2, 3),
            ClipboardHistoryPruner.entryIdsToDelete(entries, limit = 2)
        )
    }

    @Test
    fun zeroOrNegativeLimitRemovesEveryEntry() {
        val entries = listOf(entry(id = 1), entry(id = 2))

        assertArrayEquals(
            intArrayOf(1, 2),
            ClipboardHistoryPruner.entryIdsToDelete(entries, limit = 0)
        )
        assertArrayEquals(
            intArrayOf(1, 2),
            ClipboardHistoryPruner.entryIdsToDelete(entries, limit = -1)
        )
    }

    @Test
    fun doesNotRemoveEntriesWithinTheLimit() {
        val entries = listOf(entry(id = 1), entry(id = 2))

        assertArrayEquals(
            intArrayOf(),
            ClipboardHistoryPruner.entryIdsToDelete(entries, limit = 2)
        )
    }

    private fun entry(id: Int, timestamp: Long = id.toLong()) = ClipboardEntry(
        id = id,
        text = id.toString(),
        timestamp = timestamp,
    )
}
