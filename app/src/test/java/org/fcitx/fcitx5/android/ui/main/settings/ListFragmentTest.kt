/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

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
}
