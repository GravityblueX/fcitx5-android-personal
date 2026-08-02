/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

internal fun shouldInsertMixedTextSpace(before: Char?, text: String): Boolean {
    val first = text.firstOrNull() ?: return false
    return (isCjk(before) && isLatinOrDigit(first)) || (isLatinOrDigit(before) && isCjk(first))
}

private fun isCjk(char: Char?): Boolean = char != null && char in '㐀'..'鿿'

private fun isLatinOrDigit(char: Char?): Boolean = char != null &&
    (char.isDigit() || char in 'A'..'Z' || char in 'a'..'z')
