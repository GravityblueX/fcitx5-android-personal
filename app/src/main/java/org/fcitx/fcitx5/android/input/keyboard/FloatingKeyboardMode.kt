/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class FloatingKeyboardMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Disabled(R.string.disabled),
    Landscape(R.string.landscape),
    Always(R.string.always);

    fun isEnabled(configuration: Configuration): Boolean = when (this) {
        Disabled -> false
        Landscape -> configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        Always -> true
    }
}
