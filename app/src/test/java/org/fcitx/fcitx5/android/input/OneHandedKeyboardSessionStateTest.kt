/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.keyboard.OneHandedMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OneHandedKeyboardSessionStateTest {

    @Test
    fun selectedSideSurvivesStateReplacement() {
        var persistedMode = OneHandedMode.Off
        val state = OneHandedKeyboardSessionState(
            initialMode = persistedMode,
            persistMode = { persistedMode = it }
        )

        state.setMode(OneHandedMode.Right)
        val replacement = OneHandedKeyboardSessionState(initialMode = persistedMode)

        assertEquals(OneHandedMode.Right, replacement.mode)
    }

    @Test
    fun sideCanBeMirroredAndPersistsImmediately() {
        var persistedMode = OneHandedMode.Off
        val state = OneHandedKeyboardSessionState(
            persistMode = { persistedMode = it }
        )
        state.setMode(OneHandedMode.Right)

        state.setMode(OneHandedMode.Left)

        assertEquals(OneHandedMode.Left, state.mode)
        assertEquals(OneHandedMode.Left, persistedMode)
    }

    @Test
    fun explicitlyRestoringNormalModeClearsPersistedSide() {
        var persistedMode = OneHandedMode.Right
        val state = OneHandedKeyboardSessionState(
            initialMode = persistedMode,
            persistMode = { persistedMode = it }
        )

        state.setMode(OneHandedMode.Off)

        assertEquals(OneHandedMode.Off, state.mode)
        assertEquals(OneHandedMode.Off, persistedMode)
    }

    @Test
    fun invalidPersistedValueFallsBackToNormalMode() {
        assertEquals(
            OneHandedMode.Off,
            OneHandedMode.fromPreferenceValue(Int.MAX_VALUE)
        )
    }
}
