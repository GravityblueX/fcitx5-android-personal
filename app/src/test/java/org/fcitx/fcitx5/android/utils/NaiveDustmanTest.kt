/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NaiveDustmanTest {

    @Test
    fun resetDiscardsInitialValuesFromPreviousState() {
        val dustman = NaiveDustman<Boolean>()

        dustman.reset(mapOf("removed" to true))
        dustman.reset(mapOf("current" to false))
        dustman.remove("removed")

        assertFalse(dustman.dirty)
    }

    @Test
    fun forceDirtyStaysDirtyUntilReset() {
        val dustman = NaiveDustman<Boolean>()

        dustman.reset(mapOf("current" to true))
        dustman.forceDirty()
        dustman.addOrUpdate("current", true)

        assertTrue(dustman.dirty)
        dustman.reset(mapOf("current" to true))
        assertFalse(dustman.dirty)
    }

    @Test
    fun saveFailureRestoresDirtyState() = runBlocking {
        val dustman = NaiveDustman<Boolean>()
        val failure = IllegalStateException("save failed")

        dustman.forceDirty()
        dustman.reset(emptyMap())
        val result = dustman.runCatchingSave { throw failure }

        assertTrue(dustman.dirty)
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun successfulSaveKeepsCleanState() = runBlocking {
        val dustman = NaiveDustman<Boolean>()

        dustman.forceDirty()
        dustman.reset(emptyMap())
        val result = dustman.runCatchingSave {}

        assertFalse(dustman.dirty)
        assertTrue(result.isSuccess)
    }

    @Test
    fun editDuringSuccessfulSaveStaysDirty() = runBlocking {
        val dustman = NaiveDustman<Boolean>()

        dustman.forceDirty()
        dustman.reset(emptyMap())
        val result = dustman.runCatchingSave {
            dustman.forceDirty()
        }

        assertTrue(dustman.dirty)
        assertTrue(result.isSuccess)
    }

    @Test
    fun cancelledSaveRestoresDirtyStateAndRethrows() {
        val dustman = NaiveDustman<Boolean>()
        val cancellation = CancellationException("cancelled")

        dustman.forceDirty()
        dustman.reset(emptyMap())
        try {
            runBlocking {
                dustman.runCatchingSave { throw cancellation }
            }
            fail("Expected cancellation to be rethrown")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
        assertTrue(dustman.dirty)
    }
}
