/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSwipeSensitivityTest {

    @Test
    fun normalSensitivityPreservesTheSaferDefaultThreshold() {
        assertEquals(20f, SelectionSwipeSensitivity.Normal.thresholdDp)
    }

    @Test
    fun sensitivityProfilesUseIncreasingThresholdsForLowerSensitivity() {
        assertTrue(SelectionSwipeSensitivity.Low.thresholdDp > SelectionSwipeSensitivity.Normal.thresholdDp)
        assertTrue(SelectionSwipeSensitivity.Normal.thresholdDp > SelectionSwipeSensitivity.High.thresholdDp)
    }
}
