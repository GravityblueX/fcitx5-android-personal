/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTextScaleTest {

    @Test
    fun percentConvertsToExpectedScale() {
        assertEquals(0.5f, keyTextScaleForPercent(50), 0.0001f)
        assertEquals(1f, keyTextScaleForPercent(100), 0.0001f)
        assertEquals(2f, keyTextScaleForPercent(200), 0.0001f)
    }

    @Test
    fun scalePreservesRelativeKeyTextSizes() {
        assertEquals(34.5f, keyTextSize(23f, 1.5f), 0.0001f)
        assertEquals(16f, keyTextSize(10.666667f, 1.5f), 0.0001f)
    }
}
