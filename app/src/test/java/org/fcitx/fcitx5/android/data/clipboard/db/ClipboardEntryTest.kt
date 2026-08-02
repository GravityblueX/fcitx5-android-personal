/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardEntryTest {

    @Test
    fun keepsEveryDistinctTextItemInClipboardOrder() {
        val entries = ClipboardEntry.fromTexts(
            texts = listOf("first", "second", "first", null, "third"),
            timestamp = 100,
        )

        assertEquals(listOf("first", "second", "third"), entries.map { it.text })
        assertEquals(listOf(100L, 100L, 100L), entries.map { it.timestamp })
    }

    @Test
    fun appliesTransformBeforeDeduplicatingEntries() {
        val entries = ClipboardEntry.fromTexts(
            texts = listOf(" One ", "one", "Two"),
            timestamp = 100,
            transformer = { it.trim().lowercase() },
        )

        assertEquals(listOf("one", "two"), entries.map { it.text })
    }

    @Test
    fun keepsClipMetadataForEveryItem() {
        val entries = ClipboardEntry.fromTexts(
            texts = listOf("first", "second"),
            timestamp = 100,
            type = "text/custom",
            sensitive = true,
        )

        assertEquals(listOf("text/custom", "text/custom"), entries.map { it.type })
        assertEquals(listOf(true, true), entries.map { it.sensitive })
    }
}
