/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecentlyUsedTest {

    @Test
    fun keepsOnlyLatestUniqueNonblankItems() {
        assertEquals(
            listOf("second", "first"),
            normalizeRecentlyUsed(listOf("first", "", "second", "first"), 2)
        )
    }

    @Test
    fun requiresPositiveLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeRecentlyUsed(emptyList(), 0)
        }
    }
}
