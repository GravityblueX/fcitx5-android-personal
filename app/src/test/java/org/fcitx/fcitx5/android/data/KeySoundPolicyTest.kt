/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeySoundPolicyTest {

    @Test
    fun enabledModeAlwaysPlaysKeySound() {
        assertTrue(shouldPlayKeySound(InputFeedbackMode.Enabled, false))
    }

    @Test
    fun disabledModeNeverPlaysKeySound() {
        assertFalse(shouldPlayKeySound(InputFeedbackMode.Disabled, true))
    }

    @Test
    fun followingSystemModeUsesSystemSoundState() {
        assertTrue(shouldPlayKeySound(InputFeedbackMode.FollowingSystem, true))
        assertFalse(shouldPlayKeySound(InputFeedbackMode.FollowingSystem, false))
    }
}
