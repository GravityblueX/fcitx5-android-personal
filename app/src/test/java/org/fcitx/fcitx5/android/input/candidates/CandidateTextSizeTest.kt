/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateTextSizeTest {

    @Test
    fun defaultCandidateFontKeepsTheExistingBarHeight() {
        assertEquals(40, candidateItemHeightDp(20, 1f))
    }

    @Test
    fun largerFontsExpandCandidateItemsForTheirText() {
        assertEquals(56, candidateItemHeightDp(40, 1f))
        assertEquals(96, candidateItemHeightDp(64, 1.25f))
    }
}
