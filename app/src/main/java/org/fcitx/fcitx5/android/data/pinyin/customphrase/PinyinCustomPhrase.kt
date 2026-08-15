/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import org.fcitx.fcitx5.android.core.FcitxUtils
import kotlin.math.absoluteValue

data class PinyinCustomPhrase(
    val key: String,
    val order: Int,
    val value: String
) {
    val enabled: Boolean get() = order > 0

    private val orderMagnitude: Int
        get() = order.toLong().absoluteValue
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    fun copyEnabled(enabled: Boolean): PinyinCustomPhrase {
        return copy(order = if (enabled) orderMagnitude else -orderMagnitude)
    }

    fun serialize() = "$key,$orderMagnitude=${FcitxUtils.escapeForValue(value)}"
}
