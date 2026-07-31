/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipStreamTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun extractsNestedFileWithoutExplicitDirectoryEntry() {
        val destination = Files.createTempDirectory("zip-extract-").toFile()
        try {
            val extracted = ZipInputStream(
                ByteArrayInputStream(zip("shared_prefs/preferences.xml" to "settings"))
            ).use { it.extract(destination) }

            assertEquals(listOf("shared_prefs"), extracted.map { it.name })
            assertEquals(
                "settings",
                destination.resolve("shared_prefs/preferences.xml").readText()
            )
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipEntryOutsideDestination() {
        val parent = Files.createTempDirectory("zip-slip-").toFile()
        val destination = parent.resolve("import")
        val escaped = parent.resolve("escaped.txt")
        try {
            assertThrows(SecurityException::class.java) {
                ZipInputStream(
                    ByteArrayInputStream(zip("../escaped.txt" to "unsafe"))
                ).use { it.extract(destination) }
            }
            assertFalse(escaped.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun rejectsSiblingWithDestinationNamePrefix() {
        val parent = Files.createTempDirectory("zip-prefix-").toFile()
        val destination = parent.resolve("import")
        val escaped = parent.resolve("import-backup/escaped.txt")
        try {
            assertThrows(SecurityException::class.java) {
                ZipInputStream(
                    ByteArrayInputStream(zip("../import-backup/escaped.txt" to "unsafe"))
                ).use { it.extract(destination) }
            }
            assertFalse(escaped.exists())
        } finally {
            parent.deleteRecursively()
        }
    }
}
