/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyAccessibilityLabelTest {

    @Test
    fun characterKeysExposeTheirVisibleLabel() {
        assertEquals(
            KeyAccessibilityLabel.Text("A"),
            keyAccessibilityLabel(AlphabetKey("A", "@"))
        )
        assertEquals(
            KeyAccessibilityLabel.Text("7"),
            keyAccessibilityLabel(NumPadKey("7", 0xffb7))
        )
    }

    @Test
    fun iconKeysExposeLocalizedActionLabels() {
        assertEquals(
            KeyAccessibilityLabel.Resource(R.string.accessibility_shift),
            keyAccessibilityLabel(CapsKey())
        )
        assertEquals(
            KeyAccessibilityLabel.Resource(R.string.backspace),
            keyAccessibilityLabel(BackspaceKey())
        )
        assertEquals(
            KeyAccessibilityLabel.Resource(R.string.accessibility_space),
            keyAccessibilityLabel(SpaceKey())
        )
        assertEquals(
            KeyAccessibilityLabel.Resource(R.string.accessibility_enter),
            keyAccessibilityLabel(ReturnKey())
        )
    }
}
