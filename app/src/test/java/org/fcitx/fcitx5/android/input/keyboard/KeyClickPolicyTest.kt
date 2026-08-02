/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyClickPolicyTest {

    @Test
    fun releaseOutsideCanBeAcceptedWhenConfigured() {
        assertTrue(
            shouldPerformKeyClick(
                movedOutside = true,
                commitWhenReleasedOutside = true,
                longPressTriggered = false,
                repeatStarted = false,
                swipeRepeatTriggered = false,
                gestureConsumed = false
            )
        )
    }

    @Test
    fun releaseOutsideIsRejectedByDefault() {
        assertFalse(
            shouldPerformKeyClick(
                movedOutside = true,
                commitWhenReleasedOutside = false,
                longPressTriggered = false,
                repeatStarted = false,
                swipeRepeatTriggered = false,
                gestureConsumed = false
            )
        )
    }

    @Test
    fun consumedGesturesRemainRejectedWhenReleaseOutsideIsAllowed() {
        assertFalse(
            shouldPerformKeyClick(
                movedOutside = true,
                commitWhenReleasedOutside = true,
                longPressTriggered = false,
                repeatStarted = false,
                swipeRepeatTriggered = false,
                gestureConsumed = true
            )
        )
    }
}
