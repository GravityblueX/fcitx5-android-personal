/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinCustomPhraseKeyTest {

    @Test
    fun acceptsAsciiLetters() {
        assertTrue(isValidCustomPhraseKey("abc"))
        assertTrue(isValidCustomPhraseKey("AbC"))
        assertTrue(isValidCustomPhraseKey("XYZ"))
    }

    @Test
    fun rejectsEmptyOrNonLetterKeys() {
        assertFalse(isValidCustomPhraseKey(""))
        assertFalse(isValidCustomPhraseKey("a b"))
        assertFalse(isValidCustomPhraseKey("abc1"))
        assertFalse(isValidCustomPhraseKey("拼音"))
    }

    @Test
    fun preservesDisabledStateWhenEditing() {
        val original = PinyinCustomPhrase("old", -3, "old phrase")

        assertEquals(
            PinyinCustomPhrase("new", -5, "new phrase"),
            editedPinyinCustomPhrase(original, "new", 5, "new phrase")
        )
    }

    @Test
    fun createsNewEntriesAsEnabled() {
        assertEquals(
            PinyinCustomPhrase("new", 5, "new phrase"),
            editedPinyinCustomPhrase(null, "new", -5, "new phrase")
        )
    }

    @Test
    fun normalizesInvalidOrderMagnitudes() {
        assertEquals(1, editedPinyinCustomPhrase(null, "key", 0, "phrase").order)
        assertEquals(
            Int.MAX_VALUE,
            editedPinyinCustomPhrase(null, "key", Int.MIN_VALUE, "phrase").order
        )
    }

    @Test
    fun enablingNormalizesInvalidOrderMagnitudes() {
        val zeroOrder = PinyinCustomPhrase("zero", 0, "phrase").copyEnabled(true)
        val minimumOrder =
            PinyinCustomPhrase("minimum", Int.MIN_VALUE, "phrase").copyEnabled(true)

        assertTrue(zeroOrder.enabled)
        assertEquals(1, zeroOrder.order)
        assertTrue(minimumOrder.enabled)
        assertEquals(Int.MAX_VALUE, minimumOrder.order)
    }

    @Test
    fun disablingNormalizesInvalidOrderMagnitudes() {
        val zeroOrder = PinyinCustomPhrase("zero", 0, "phrase").copyEnabled(false)
        val minimumOrder =
            PinyinCustomPhrase("minimum", Int.MIN_VALUE, "phrase").copyEnabled(false)

        assertFalse(zeroOrder.enabled)
        assertEquals(-1, zeroOrder.order)
        assertFalse(minimumOrder.enabled)
        assertEquals(-Int.MAX_VALUE, minimumOrder.order)
    }

    @Test
    fun togglingPreservesValidOrderMagnitude() {
        assertEquals(-7, PinyinCustomPhrase("key", 7, "phrase").copyEnabled(false).order)
        assertEquals(7, PinyinCustomPhrase("key", -7, "phrase").copyEnabled(true).order)
    }

    @Test
    fun serializingNormalizesInvalidOrderMagnitudes() {
        assertEquals("zero,1=phrase", PinyinCustomPhrase("zero", 0, "phrase").serialize())
        assertEquals(
            "minimum,${Int.MAX_VALUE}=phrase",
            PinyinCustomPhrase("minimum", Int.MIN_VALUE, "phrase").serialize()
        )
    }
}
