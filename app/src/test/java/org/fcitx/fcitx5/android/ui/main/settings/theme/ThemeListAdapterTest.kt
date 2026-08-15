/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeListAdapterTest {

    @Test
    fun removalKeepsPositionsBeforeRemovedItem() {
        assertEquals(-1, adjustPositionAfterRemoval(-1, 3))
        assertEquals(1, adjustPositionAfterRemoval(1, 3))
        assertEquals(2, adjustPositionAfterRemoval(2, 3))
    }

    @Test
    fun removalResetsRemovedPositionAndShiftsFollowingItems() {
        assertEquals(-1, adjustPositionAfterRemoval(3, 3))
        assertEquals(3, adjustPositionAfterRemoval(4, 3))
        assertEquals(4, adjustPositionAfterRemoval(5, 3))
    }

    @Test
    fun movingItemToFrontUpdatesEveryAffectedPosition() {
        assertEquals(-1, adjustPositionAfterMoveToFront(-1, 4, 1))
        assertEquals(2, adjustPositionAfterMoveToFront(1, 4, 1))
        assertEquals(3, adjustPositionAfterMoveToFront(2, 4, 1))
        assertEquals(4, adjustPositionAfterMoveToFront(3, 4, 1))
        assertEquals(1, adjustPositionAfterMoveToFront(4, 4, 1))
        assertEquals(5, adjustPositionAfterMoveToFront(5, 4, 1))
    }

    @Test
    fun movingFirstItemKeepsPositionsUnchanged() {
        assertEquals(1, adjustPositionAfterMoveToFront(1, 1, 1))
        assertEquals(2, adjustPositionAfterMoveToFront(2, 1, 1))
    }
}
