/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

internal fun keyTextScaleForPercent(percent: Int): Float = percent / 100f

internal fun keyTextSize(baseSize: Float, scale: Float): Float = baseSize * scale
