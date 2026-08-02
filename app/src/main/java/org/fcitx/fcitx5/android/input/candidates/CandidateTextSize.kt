/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import kotlin.math.ceil
import kotlin.math.max

internal const val DefaultCandidateItemHeightDp = 40

internal fun candidateItemHeightDp(fontSizeSp: Int, fontScale: Float): Int = max(
    DefaultCandidateItemHeightDp,
    ceil(fontSizeSp * fontScale).toInt() + 16
)
