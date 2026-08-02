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
    fun returnKeyLabelsFollowTheirEditorActionIcons() {
        val expectedLabels = mapOf(
            R.drawable.ic_baseline_arrow_forward_24 to R.string.accessibility_go,
            R.drawable.ic_baseline_search_24 to R.string.accessibility_search,
            R.drawable.ic_baseline_send_24 to R.string.accessibility_send,
            R.drawable.ic_baseline_keyboard_tab_24 to R.string.accessibility_next,
            R.drawable.ic_baseline_done_24 to R.string.accessibility_done,
            R.drawable.ic_baseline_keyboard_tab_reverse_24 to R.string.accessibility_previous,
            R.drawable.ic_baseline_keyboard_return_24 to R.string.accessibility_enter
        )

        expectedLabels.forEach { (drawable, label) ->
            assertEquals(
                KeyAccessibilityLabel.Resource(label),
                returnKeyAccessibilityLabel(drawable)
            )
        }
    }

    @Test
    fun capsStateLabelsDescribeTemporaryAndLockedUppercase() {
        assertEquals(
            null,
            capsStateAccessibilityLabel(TextKeyboard.CapsState.None)
        )
        assertEquals(
            KeyAccessibilityLabel.Resource(R.string.accessibility_shift_once),
            capsStateAccessibilityLabel(TextKeyboard.CapsState.Once)
        )
        assertEquals(
            KeyAccessibilityLabel.Resource(R.string.accessibility_caps_lock),
            capsStateAccessibilityLabel(TextKeyboard.CapsState.Lock)
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
