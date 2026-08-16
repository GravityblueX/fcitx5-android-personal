/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FileBackedItemRemovalTest {

    @Test
    fun restoresFailedBatchItemsAtOriginalRelativePositions() {
        val firstFailure = IllegalStateException("first failure")
        val laterFailure = IllegalStateException("later failure")
        val removed = mutableListOf<String>()
        val restored = mutableListOf<Pair<Int, String>>()

        val failure = applyFileBackedRemovals(
            listOf(5 to "late", 3 to "removed", 1 to "early"),
            remove = { item ->
                when (item) {
                    "early" -> Result.failure(firstFailure)
                    "late" -> Result.failure(laterFailure)
                    else -> Result.success(Unit)
                }
            },
            onRemoved = removed::add,
            restore = { index, item -> restored.add(index to item) },
        )

        assertSame(firstFailure, failure)
        assertEquals(listOf("removed"), removed)
        assertEquals(listOf(1 to "early", 4 to "late"), restored)
    }

    @Test
    fun returnsNoFailureWhenEveryRemovalSucceeds() {
        val removed = mutableListOf<String>()

        val failure = applyFileBackedRemovals(
            listOf(2 to "second", 0 to "first"),
            remove = { Result.success(Unit) },
            onRemoved = removed::add,
            restore = { _, _ -> error("Unexpected restore") },
        )

        assertNull(failure)
        assertEquals(listOf("first", "second"), removed)
    }

    @Test
    fun restoresItemWhenRemovalThrows() {
        val thrown = IllegalStateException("thrown failure")
        val restored = mutableListOf<Pair<Int, String>>()

        val failure = applyFileBackedRemovals(
            listOf(2 to "item"),
            remove = { throw thrown },
            onRemoved = { error("Unexpected removal") },
            restore = { index, item -> restored.add(index to item) },
        )

        assertSame(thrown, failure)
        assertEquals(listOf(2 to "item"), restored)
    }
}
