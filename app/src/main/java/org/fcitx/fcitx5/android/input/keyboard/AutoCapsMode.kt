/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType

enum class AutoCapsMode {
    None,
    Once,
    Lock;

    companion object {
        const val RequestFlags = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
}

internal fun autoCapsModeFor(cursorCapsMode: Int): AutoCapsMode {
    return when {
        cursorCapsMode and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> AutoCapsMode.Lock
        cursorCapsMode and (InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0 -> AutoCapsMode.Once
        else -> AutoCapsMode.None
    }
}
