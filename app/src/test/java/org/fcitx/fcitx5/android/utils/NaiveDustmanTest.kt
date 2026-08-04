/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertFalse
import org.junit.Test

class NaiveDustmanTest {

    @Test
    fun resetDiscardsInitialValuesFromPreviousState() {
        val dustman = NaiveDustman<Boolean>()

        dustman.reset(mapOf("removed" to true))
        dustman.reset(mapOf("current" to false))
        dustman.remove("removed")

        assertFalse(dustman.dirty)
    }
}
