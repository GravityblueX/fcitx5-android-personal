/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

internal const val DoubleSpacePeriodTimeoutMillis = 400L

internal fun shouldReplaceDoubleSpacePeriod(
    enabled: Boolean,
    elapsedMillis: Long?,
    precedingCharacter: Char?,
    hasCollapsedSelection: Boolean,
    canInsertPeriod: Boolean,
    isRepeating: Boolean
): Boolean = enabled &&
    elapsedMillis != null &&
    elapsedMillis in 0..DoubleSpacePeriodTimeoutMillis &&
    precedingCharacter == ' ' &&
    hasCollapsedSelection &&
    !isRepeating &&
    canInsertPeriod
