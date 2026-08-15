/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardChangeDeduplicatorTest {

    @Test
    fun ignoresRepeatedContentWithSameSourceTimestamp() {
        val deduplicator = ClipboardChangeDeduplicator()

        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 100L)))
        assertTrue(deduplicator.shouldIgnore(change(sourceTimestamp = 100L)))
    }

    @Test
    fun acceptsDifferentContentWithSameSourceTimestamp() {
        val deduplicator = ClipboardChangeDeduplicator()

        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 100L, text = "first")))
        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 100L, text = "second")))
    }

    @Test
    fun acceptsRepeatedContentWithNewSourceTimestamp() {
        val deduplicator = ClipboardChangeDeduplicator()

        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 100L)))
        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 101L)))
    }

    @Test
    fun acceptsChangedMimeType() {
        val deduplicator = ClipboardChangeDeduplicator()

        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 100L)))
        assertFalse(
            deduplicator.shouldIgnore(
                change(sourceTimestamp = 100L, type = "text/custom")
            )
        )
    }

    @Test
    fun acceptsChangedSensitiveFlag() {
        val deduplicator = ClipboardChangeDeduplicator()

        assertFalse(deduplicator.shouldIgnore(change(sourceTimestamp = 100L)))
        assertFalse(
            deduplicator.shouldIgnore(
                change(sourceTimestamp = 100L, sensitive = true)
            )
        )
    }

    @Test
    fun ignoresLegacyDuplicateWithinArrivalWindow() {
        val deduplicator = ClipboardChangeDeduplicator(legacyDuplicateWindowMillis = 100L)

        assertFalse(deduplicator.shouldIgnore(change(receivedAtElapsedMillis = 1_000L)))
        assertTrue(deduplicator.shouldIgnore(change(receivedAtElapsedMillis = 1_099L)))
    }

    @Test
    fun acceptsLegacyDuplicateAtWindowBoundary() {
        val deduplicator = ClipboardChangeDeduplicator(legacyDuplicateWindowMillis = 100L)

        assertFalse(deduplicator.shouldIgnore(change(receivedAtElapsedMillis = 1_000L)))
        assertFalse(deduplicator.shouldIgnore(change(receivedAtElapsedMillis = 1_100L)))
    }

    @Test
    fun acceptsLegacyEventWhenArrivalTimeMovesBackwards() {
        val deduplicator = ClipboardChangeDeduplicator()

        assertFalse(deduplicator.shouldIgnore(change(receivedAtElapsedMillis = 1_000L)))
        assertFalse(deduplicator.shouldIgnore(change(receivedAtElapsedMillis = 900L)))
    }

    private fun change(
        sourceTimestamp: Long? = null,
        receivedAtElapsedMillis: Long = 0L,
        text: String = "text",
        type: String = "text/plain",
        sensitive: Boolean = false,
    ) = ClipboardChangeSnapshot(
        sourceTimestamp = sourceTimestamp,
        receivedAtElapsedMillis = receivedAtElapsedMillis,
        content = listOf(ClipboardContentSignature(text, type, sensitive)),
    )
}
