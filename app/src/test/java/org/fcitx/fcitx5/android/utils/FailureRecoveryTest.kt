/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FailureRecoveryTest {

    @Test
    fun reportsCleanupFailureWithoutChangingSuccessfulResult() {
        val cleanupFailure = IOException("cleanup")
        var reportedFailure: Throwable? = null

        val result = runWithCleanup(
            cleanup = { Result.failure(cleanupFailure) },
            onCleanupFailure = { reportedFailure = it },
            block = { "result" },
        )

        assertEquals("result", result)
        assertSame(cleanupFailure, reportedFailure)
    }

    @Test
    fun suppressesCleanupFailureOnPrimaryFailure() {
        val primaryFailure = IOException("primary")
        val cleanupFailure = IOException("cleanup")
        var reportedFailure: Throwable? = null

        val thrown = assertThrows(IOException::class.java) {
            runWithCleanup(
                cleanup = { Result.failure(cleanupFailure) },
                onCleanupFailure = { reportedFailure = it },
                block = { throw primaryFailure },
            )
        }

        assertSame(primaryFailure, thrown)
        assertArrayEquals(arrayOf(cleanupFailure), thrown.suppressed)
        assertNull(reportedFailure)
    }

    @Test
    fun handlesCleanupCallbackThrowing() {
        val primaryFailure = IOException("primary")
        val cleanupFailure = IOException("cleanup")

        val thrown = assertThrows(IOException::class.java) {
            runWithCleanup(
                cleanup = { throw cleanupFailure },
                onCleanupFailure = {},
                block = { throw primaryFailure },
            )
        }

        assertSame(primaryFailure, thrown)
        assertArrayEquals(arrayOf(cleanupFailure), thrown.suppressed)
    }

    @Test
    fun doesNotSuppressPrimaryFailureOnItself() {
        val primaryFailure = IOException("primary")

        val thrown = assertThrows(IOException::class.java) {
            runWithCleanup(
                cleanup = { Result.failure(primaryFailure) },
                onCleanupFailure = {},
                block = { throw primaryFailure },
            )
        }

        assertSame(primaryFailure, thrown)
        assertTrue(thrown.suppressed.isEmpty())
    }
}
