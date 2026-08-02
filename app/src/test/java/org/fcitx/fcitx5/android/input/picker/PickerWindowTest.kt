/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerWindowTest {

    @Test
    fun emojiPickerPrefersRecentlyUsedItems() {
        assertEquals(0, initialPickerCategoryIndex(PickerWindow.Key.Emoji, true))
    }

    @Test
    fun emptyEmojiHistoryAndOtherPickersUseFirstCategory() {
        assertEquals(1, initialPickerCategoryIndex(PickerWindow.Key.Emoji, false))
        assertEquals(1, initialPickerCategoryIndex(PickerWindow.Key.Symbol, true))
        assertEquals(1, initialPickerCategoryIndex(PickerWindow.Key.Emoticon, true))
    }
}
