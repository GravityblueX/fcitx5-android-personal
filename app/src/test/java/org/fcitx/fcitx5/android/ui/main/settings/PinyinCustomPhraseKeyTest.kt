/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

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
}
