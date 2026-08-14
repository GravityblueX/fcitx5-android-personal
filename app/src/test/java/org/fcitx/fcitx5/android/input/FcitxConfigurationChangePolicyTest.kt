/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.pm.ActivityInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcitxConfigurationChangePolicyTest {

    @Test
    fun skipsResetForAppearanceOnlyChanges() {
        assertFalse(
            FcitxConfigurationChangePolicy.requiresReset(ActivityInfo.CONFIG_UI_MODE)
        )
        assertFalse(FcitxConfigurationChangePolicy.requiresReset(0))
    }

    @Test
    fun resetsForInputRelevantChanges() {
        assertTrue(
            FcitxConfigurationChangePolicy.requiresReset(ActivityInfo.CONFIG_ORIENTATION)
        )
        assertTrue(
            FcitxConfigurationChangePolicy.requiresReset(
                ActivityInfo.CONFIG_UI_MODE or ActivityInfo.CONFIG_ORIENTATION
            )
        )
    }
}
