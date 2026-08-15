/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberRowGestureTest {

    @Test
    fun leftToRightLayoutCollapsesOnRightSwipe() {
        assertTrue(
            shouldCollapseNumberRow(
                startX = 10f,
                currentX = 41f,
                threshold = 30f,
                leftToRight = true,
            )
        )
    }

    @Test
    fun rightToLeftLayoutCollapsesOnLeftSwipe() {
        assertTrue(
            shouldCollapseNumberRow(
                startX = 41f,
                currentX = 10f,
                threshold = 30f,
                leftToRight = false,
            )
        )
    }

    @Test
    fun oppositeDirectionDoesNotCollapse() {
        assertFalse(
            shouldCollapseNumberRow(
                startX = 41f,
                currentX = 10f,
                threshold = 30f,
                leftToRight = true,
            )
        )
        assertFalse(
            shouldCollapseNumberRow(
                startX = 10f,
                currentX = 41f,
                threshold = 30f,
                leftToRight = false,
            )
        )
    }

    @Test
    fun swipeMustExceedThreshold() {
        assertFalse(
            shouldCollapseNumberRow(
                startX = 10f,
                currentX = 40f,
                threshold = 30f,
                leftToRight = true,
            )
        )
    }
}
