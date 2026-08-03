/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationManagerTest {

    @Test
    fun malformedEntriesAreIgnored() {
        val valid = RawConfig(
            "0",
            arrayOf(
                RawConfig(PunctuationManager.KEY, ","),
                RawConfig(PunctuationManager.MAPPING, "，"),
                RawConfig(PunctuationManager.ALT_MAPPING, "")
            )
        )
        val malformed = RawConfig("1", arrayOf(RawConfig(PunctuationManager.KEY, ".")))
        val raw = RawConfig(
            arrayOf(RawConfig("cfg", arrayOf(RawConfig(PunctuationManager.ENTRIES, arrayOf(valid, malformed)))) )
        )

        assertEquals(
            listOf(PunctuationMapEntry(",", "，", "")),
            PunctuationManager.parseRawConfig(raw)
        )
    }

    @Test
    fun missingEntriesProduceAnEmptyList() {
        assertTrue(PunctuationManager.parseRawConfig(RawConfig()).isEmpty())
    }
}
