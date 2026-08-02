/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCapsModeTest {

    @Test
    fun characterCapitalsStayLocked() {
        assertEquals(
            AutoCapsMode.Lock,
            autoCapsModeFor(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        )
    }

    @Test
    fun wordAndSentenceCapitalsUseSingleShift() {
        assertEquals(
            AutoCapsMode.Once,
            autoCapsModeFor(InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        )
        assertEquals(
            AutoCapsMode.Once,
            autoCapsModeFor(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        )
    }

    @Test
    fun noRequestedCapitalizationLeavesKeyboardLowercase() {
        assertEquals(AutoCapsMode.None, autoCapsModeFor(0))
    }

    @Test
    fun characterCapitalsWinOverSingleShiftModes() {
        assertEquals(
            AutoCapsMode.Lock,
            autoCapsModeFor(
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            )
        )
    }
}
