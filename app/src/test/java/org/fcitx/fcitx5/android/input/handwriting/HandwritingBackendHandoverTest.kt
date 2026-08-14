/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class HandwritingBackendHandoverTest {

    private class TrackedCloseable(
        private val failure: RuntimeException? = null,
    ) : Closeable {
        var closed = false

        override fun close() {
            closed = true
            failure?.let { throw it }
        }
    }

    @Test
    fun acceptedHandoverClosesPreviousBackend() {
        val replacement = TrackedCloseable()
        val previous = TrackedCloseable()
        var completion: Boolean? = null

        HandwritingBackendHandover.complete(replacement, previous) {
            completion = it
        }

        assertFalse(replacement.closed)
        assertTrue(previous.closed)
        assertEquals(true, completion)
    }

    @Test
    fun staleHandoverClosesReplacementAndCompletes() {
        val replacement = TrackedCloseable()
        var completion: Boolean? = null

        HandwritingBackendHandover.complete(replacement, null) {
            completion = it
        }

        assertTrue(replacement.closed)
        assertEquals(false, completion)
    }

    @Test
    fun closeFailureStillCompletesHandover() {
        val failure = IllegalStateException("close failed")
        val replacement = TrackedCloseable(failure)
        var completion: Boolean? = null

        assertThrows(IllegalStateException::class.java) {
            HandwritingBackendHandover.complete(replacement, null) {
                completion = it
            }
        }

        assertTrue(replacement.closed)
        assertEquals(false, completion)
    }
}
