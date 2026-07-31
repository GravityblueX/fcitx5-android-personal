/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.keyboard.OneHandedMode

/**
 * Keeps the one-handed layout choice across InputView replacements and forwards explicit changes
 * to persistent storage. Landscape and automatic floating mode only suspend the visual layout;
 * they do not erase the remembered side.
 */
internal class OneHandedKeyboardSessionState(
    initialMode: OneHandedMode = OneHandedMode.Off,
    private val persistMode: (OneHandedMode) -> Unit = {},
) {
    var mode: OneHandedMode = initialMode
        private set

    fun setMode(value: OneHandedMode) {
        if (mode == value) return
        mode = value
        persistMode(value)
    }
}
