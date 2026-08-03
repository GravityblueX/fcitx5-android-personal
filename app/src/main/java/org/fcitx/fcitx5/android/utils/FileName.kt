/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

fun String.safeFileName(default: String = "import"): String {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    return fileName.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: default
}
