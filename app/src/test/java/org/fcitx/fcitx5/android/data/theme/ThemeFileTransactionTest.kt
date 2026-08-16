/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ThemeFileTransactionTest {

    @Test
    fun preservesPrimaryFailureAndRecordsEveryRollbackFailure() {
        val primary = IllegalStateException("primary")
        val firstRollback = IllegalStateException("first rollback")
        val secondRollback = IllegalStateException("second rollback")

        primary.addSuppressedFailures(
            listOf(
                Result.failure(firstRollback),
                Result.success(Unit),
                Result.failure(secondRollback),
            )
        )

        assertArrayEquals(arrayOf(firstRollback, secondRollback), primary.suppressed)
    }
}
