/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard.CapsState
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsStateTest {

    @Test
    fun regularSingleTapUsesOneCharacterUppercase() {
        assertEquals(CapsState.Once, nextCapsState(CapsState.None, lock = false, singleTapLocks = false))
        assertEquals(CapsState.None, nextCapsState(CapsState.Once, lock = false, singleTapLocks = false))
    }

    @Test
    fun configuredSingleTapTogglesCapsLock() {
        assertEquals(CapsState.Lock, nextCapsState(CapsState.None, lock = false, singleTapLocks = true))
        assertEquals(CapsState.None, nextCapsState(CapsState.Lock, lock = false, singleTapLocks = true))
    }

    @Test
    fun explicitLockActionAlwaysTogglesCapsLock() {
        assertEquals(CapsState.Lock, nextCapsState(CapsState.Once, lock = true, singleTapLocks = false))
        assertEquals(CapsState.None, nextCapsState(CapsState.Lock, lock = true, singleTapLocks = false))
    }
}
