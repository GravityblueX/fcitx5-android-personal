/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.utils.runWithRollback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ThemeFileTransactionTest {

    @Test
    fun preservesPrimaryFailureAndRecordsEveryRollbackFailure() {
        val primary = IllegalStateException("primary")
        val firstRollback = IllegalStateException("first rollback")
        val secondRollback = IllegalStateException("second rollback")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runWithRollback(
                rollback = {
                    listOf(
                        Result.failure(firstRollback),
                        Result.success(Unit),
                        Result.failure(secondRollback),
                    )
                },
            ) {
                throw primary
            }
        }

        assertSame(primary, thrown)
        assertArrayEquals(arrayOf(firstRollback, secondRollback), primary.suppressed)
    }

    @Test
    fun preservesPrimaryFailureWhenRollbackItselfThrows() {
        val primary = IllegalStateException("primary")
        val rollback = IllegalStateException("rollback")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runWithRollback(
                rollback = { throw rollback },
            ) {
                throw primary
            }
        }

        assertSame(primary, thrown)
        assertArrayEquals(arrayOf(rollback), primary.suppressed)
    }
}
