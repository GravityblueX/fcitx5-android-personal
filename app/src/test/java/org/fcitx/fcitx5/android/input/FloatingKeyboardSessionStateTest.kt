/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Configuration
import org.fcitx.fcitx5.android.input.keyboard.FloatingKeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingKeyboardSessionStateTest {

    @Test
    fun portraitManualFloatingReturnsAfterLandscapeRoundTrip() {
        val state = FloatingKeyboardSessionState()
        state.setManualOverride(
            true,
            FloatingKeyboardMode.Landscape,
            Configuration.ORIENTATION_PORTRAIT
        )

        assertEquals(
            true,
            state.manualOverrideFor(
                FloatingKeyboardMode.Landscape,
                Configuration.ORIENTATION_PORTRAIT
            )
        )
        assertNull(
            state.manualOverrideFor(
                FloatingKeyboardMode.Landscape,
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
        assertEquals(
            true,
            state.manualOverrideFor(
                FloatingKeyboardMode.Landscape,
                Configuration.ORIENTATION_PORTRAIT
            )
        )
    }

    @Test
    fun portraitManualDockDoesNotBlockLandscapeAutomaticPolicy() {
        val state = FloatingKeyboardSessionState()
        state.setManualOverride(
            false,
            FloatingKeyboardMode.Landscape,
            Configuration.ORIENTATION_PORTRAIT
        )

        assertNull(
            state.manualOverrideFor(
                FloatingKeyboardMode.Landscape,
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
    }

    @Test
    fun disabledModeKeepsManualFloatingChoiceAcrossRotation() {
        val state = FloatingKeyboardSessionState()
        state.setManualOverride(
            true,
            FloatingKeyboardMode.Disabled,
            Configuration.ORIENTATION_PORTRAIT
        )

        assertEquals(
            true,
            state.manualOverrideFor(
                FloatingKeyboardMode.Disabled,
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
    }

    @Test
    fun changingModeInvalidatesAnOverrideCreatedForAnotherPolicy() {
        val state = FloatingKeyboardSessionState()
        state.setManualOverride(
            false,
            FloatingKeyboardMode.Disabled,
            Configuration.ORIENTATION_PORTRAIT
        )

        assertNull(
            state.manualOverrideFor(
                FloatingKeyboardMode.Landscape,
                Configuration.ORIENTATION_PORTRAIT
            )
        )
    }

    @Test
    fun alwaysModeKeepsItsManualSessionChoiceAcrossRotation() {
        val state = FloatingKeyboardSessionState()
        state.setManualOverride(
            false,
            FloatingKeyboardMode.Always,
            Configuration.ORIENTATION_PORTRAIT
        )

        assertEquals(
            false,
            state.manualOverrideFor(
                FloatingKeyboardMode.Always,
                Configuration.ORIENTATION_LANDSCAPE
            )
        )
    }
}
