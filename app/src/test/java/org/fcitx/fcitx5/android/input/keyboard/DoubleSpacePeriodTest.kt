/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleSpacePeriodTest {

    @Test
    fun replacesOnlyConsecutiveSpacesWithinTimeout() {
        assertTrue(shouldReplaceDoubleSpacePeriod(true, DoubleSpacePeriodTimeoutMillis, ' ', true, true, false))
        assertFalse(shouldReplaceDoubleSpacePeriod(true, DoubleSpacePeriodTimeoutMillis + 1, ' ', true, true, false))
        assertFalse(shouldReplaceDoubleSpacePeriod(true, 100, 'a', true, true, false))
    }

    @Test
    fun excludesDisabledAndUnsafeContexts() {
        assertFalse(shouldReplaceDoubleSpacePeriod(false, 100, ' ', true, true, false))
        assertFalse(shouldReplaceDoubleSpacePeriod(true, 100, ' ', false, true, false))
        assertFalse(shouldReplaceDoubleSpacePeriod(true, 100, ' ', true, false, false))
        assertFalse(shouldReplaceDoubleSpacePeriod(true, 100, ' ', true, true, true))
    }
}
