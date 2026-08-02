/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SelectionSwipeSensitivity(
    override val stringRes: Int,
    val thresholdDp: Float,
) : ManagedPreferenceEnum {
    Low(R.string.swipe_sensitivity_low, 28f),
    Normal(R.string.swipe_sensitivity_normal, 20f),
    High(R.string.swipe_sensitivity_high, 12f),
}
