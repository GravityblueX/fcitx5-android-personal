/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ListFragmentTest {

    @Test
    fun enumLabelsFallBackForMissingTranslations() {
        assertEquals(
            listOf("Translated A", "b"),
            enumEntryLabels(
                entries = listOf("a", "b"),
                entriesI18n = listOf("Translated A"),
            )
        )
    }

    @Test
    fun enumListUsesTranslatedLabel() {
        assertEquals(
            "Translated B",
            enumListEntryLabel(
                value = "b",
                entries = listOf("a", "b"),
                entriesI18n = listOf("Translated A", "Translated B"),
            )
        )
    }

    @Test
    fun enumListFallsBackForStaleValue() {
        assertEquals(
            "removed",
            enumListEntryLabel(
                value = "removed",
                entries = listOf("a", "b"),
                entriesI18n = listOf("Translated A", "Translated B"),
            )
        )
    }

    @Test
    fun enumListFallsBackForMissingTranslation() {
        assertEquals(
            "b",
            enumListEntryLabel(
                value = "b",
                entries = listOf("a", "b"),
                entriesI18n = listOf("Translated A"),
            )
        )
    }

    @Test
    fun integerListPreservesValidValuesAndOrder() {
        val items = arrayOf(
            RawConfig("0", Int.MIN_VALUE.toString()),
            RawConfig("1", "-1"),
            RawConfig("2", "0"),
            RawConfig("3", Int.MAX_VALUE.toString()),
        )

        assertEquals(
            listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE),
            parseIntegerListEntries(items),
        )
    }

    @Test
    fun integerListSkipsMalformedAndOutOfRangeValues() {
        val items = arrayOf(
            RawConfig("0", "invalid"),
            RawConfig("1", (Int.MIN_VALUE.toLong() - 1).toString()),
            RawConfig("2", "42"),
            RawConfig("3", (Int.MAX_VALUE.toLong() + 1).toString()),
        )

        assertEquals(listOf(42), parseIntegerListEntries(items))
    }

    @Test
    fun missingIntegerListUsesNoEntries() {
        assertEquals(emptyList<Int>(), parseIntegerListEntries(null))
    }
}
