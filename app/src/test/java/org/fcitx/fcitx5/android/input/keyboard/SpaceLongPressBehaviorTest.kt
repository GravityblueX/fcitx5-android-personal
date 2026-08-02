/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceLongPressBehaviorTest {

    @Test
    fun repeatSpacesIsAnExplicitLongPressMode() {
        assertTrue(shouldRepeatSpacesOnLongPress(SpaceLongPressBehavior.RepeatSpaces))
    }

    @Test
    fun existingLongPressModesKeepTheirOriginalBehavior() {
        assertFalse(shouldRepeatSpacesOnLongPress(SpaceLongPressBehavior.None))
        assertFalse(shouldRepeatSpacesOnLongPress(SpaceLongPressBehavior.Enumerate))
        assertFalse(shouldRepeatSpacesOnLongPress(SpaceLongPressBehavior.ToggleActivate))
        assertFalse(shouldRepeatSpacesOnLongPress(SpaceLongPressBehavior.ShowPicker))
    }
}
