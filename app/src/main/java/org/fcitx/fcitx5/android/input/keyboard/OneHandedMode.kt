/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

enum class OneHandedMode {
    Off,
    Left,
    Right;

    val preferenceValue: Int
        get() = ordinal

    companion object {
        fun fromPreferenceValue(value: Int): OneHandedMode =
            entries.getOrNull(value) ?: Off
    }
}
