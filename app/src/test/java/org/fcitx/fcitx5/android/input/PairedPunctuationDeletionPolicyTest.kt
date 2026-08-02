/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedPunctuationDeletionPolicyTest {
    @Test
    fun recognizesSupportedPairsOnly() {
        listOf('(' to ')', '（' to '）', '【' to '】', '“' to '”', '"' to '"').forEach { (before, after) ->
            assertTrue(PairedPunctuationDeletionPolicy.shouldDeletePair(before, after))
        }
        assertFalse(PairedPunctuationDeletionPolicy.shouldDeletePair('(' , ']'))
        assertFalse(PairedPunctuationDeletionPolicy.shouldDeletePair(null, ')'))
        assertFalse(PairedPunctuationDeletionPolicy.shouldDeletePair('a', 'b'))
    }
}
