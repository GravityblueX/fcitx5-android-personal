/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.util.zip.ZipInputStream

private const val DEFAULT_MAX_ZIP_ENTRIES = 10_000
private const val DEFAULT_MAX_ZIP_ENTRY_BYTES = 256L * 1024 * 1024
private const val DEFAULT_MAX_ZIP_TOTAL_BYTES = 1024L * 1024 * 1024

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(
    destDir: File,
    maxEntries: Int = DEFAULT_MAX_ZIP_ENTRIES,
    maxEntryBytes: Long = DEFAULT_MAX_ZIP_ENTRY_BYTES,
    maxTotalBytes: Long = DEFAULT_MAX_ZIP_TOTAL_BYTES,
): List<File> {
    require(maxEntries > 0) { "maxEntries must be positive" }
    require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
    require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }

    val canonicalDest = destDir.canonicalFile
    canonicalDest.mkdirs()
    val canonicalDestPrefix = canonicalDest.path + File.separator
    var entryCount = 0
    var extractedBytes = 0L
    var entry = nextEntry
    while (entry != null) {
        if (++entryCount > maxEntries) {
            throw SecurityException("Zip archive contains too many entries")
        }
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
            try {
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = read(buffer)
                        if (read < 0) break
                        val nextEntryBytes = entryBytes + read
                        val nextTotalBytes = extractedBytes + read
                        if (nextEntryBytes > maxEntryBytes) {
                            throw SecurityException("Zip entry is too large: ${entry.name}")
                        }
                        if (nextTotalBytes > maxTotalBytes) {
                            throw SecurityException("Zip archive is too large")
                        }
                        output.write(buffer, 0, read)
                        entryBytes = nextEntryBytes
                        extractedBytes = nextTotalBytes
                    }
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
        closeEntry()
        entry = nextEntry
    }
    return canonicalDest.listFiles()?.toList() ?: emptyList()
}
