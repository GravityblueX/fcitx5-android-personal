/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import java.io.File

fun String.safeFileName(default: String = "import"): String {
    val fileName = substringAfterLast('/').substringAfterLast('\\')
    return fileName.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: default
}

fun File.resolveDirectChild(name: String): File {
    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
    val directory = canonicalFile
    return File(directory, name).canonicalFile.also { require(it.parentFile == directory) }
}
