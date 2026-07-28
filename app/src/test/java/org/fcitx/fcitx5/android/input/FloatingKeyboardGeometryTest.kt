/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingKeyboardGeometryTest {

    @Test
    fun keepsDesiredSizeWhenBothAxesFit() {
        assertEquals(
            900 to 600,
            fitFloatingKeyboardSize(
                desiredWidth = 900,
                desiredHeight = 600,
                minWidth = 280,
                maxWidth = 2000,
                minHeight = 128,
                maxHeight = 1000
            )
        )
    }

    @Test
    fun shrinksBothAxesUniformlyWhenRotatedHeightIsLimited() {
        assertEquals(
            750 to 500,
            fitFloatingKeyboardSize(
                desiredWidth = 900,
                desiredHeight = 600,
                minWidth = 280,
                maxWidth = 2000,
                minHeight = 128,
                maxHeight = 500
            )
        )
    }

    @Test
    fun originalSizeReturnsWhenSafeAreaBecomesLargeAgain() {
        val landscape = fitFloatingKeyboardSize(
            desiredWidth = 900,
            desiredHeight = 600,
            minWidth = 280,
            maxWidth = 2000,
            minHeight = 128,
            maxHeight = 500
        )
        assertEquals(750 to 500, landscape)
        assertEquals(
            900 to 600,
            fitFloatingKeyboardSize(
                desiredWidth = 900,
                desiredHeight = 600,
                minWidth = 280,
                maxWidth = 1000,
                minHeight = 128,
                maxHeight = 2000
            )
        )
    }

    @Test
    fun growsBothAxesUniformlyToMinimumDisplayableSize() {
        assertEquals(
            300 to 150,
            fitFloatingKeyboardSize(
                desiredWidth = 200,
                desiredHeight = 100,
                minWidth = 300,
                maxWidth = 1000,
                minHeight = 150,
                maxHeight = 1000
            )
        )
    }
}
