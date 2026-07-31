/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.util.zip.ZipInputStream

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    val canonicalDest = destDir.canonicalFile
    canonicalDest.mkdirs()
    val canonicalDestPrefix = canonicalDest.path + File.separator
    var entry = nextEntry
    while (entry != null) {
        val target = File(canonicalDest, entry.name).canonicalFile
        if (target != canonicalDest && !target.path.startsWith(canonicalDestPrefix)) {
            throw SecurityException("Zip entry escapes destination: ${entry.name}")
        }
        if (entry.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) {
                throw IllegalStateException("Cannot create directory: $target")
            }
        } else {
            val parent = target.parentFile
            if (parent != null && !parent.mkdirs() && !parent.isDirectory) {
                throw IllegalStateException("Cannot create directory: $parent")
            }
            target.outputStream().use(::copyTo)
        }
        closeEntry()
        entry = nextEntry
    }
    return canonicalDest.listFiles()?.toList() ?: emptyList()
}
