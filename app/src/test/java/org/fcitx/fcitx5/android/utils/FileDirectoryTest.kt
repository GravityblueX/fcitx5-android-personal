/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileDirectoryTest {

    @Test
    fun createsMissingDirectoryHierarchy() {
        val root = Files.createTempDirectory("file-directory-").toFile()
        try {
            val directory = root.resolve("nested/directory")

            val result = directory.ensureDirectory()

            assertSame(directory, result)
            assertTrue(directory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsExistingDirectory() {
        val directory = Files.createTempDirectory("file-directory-").toFile()
        try {
            assertSame(directory, directory.ensureDirectory())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsExistingFileWithoutChangingIt() {
        val root = Files.createTempDirectory("file-directory-").toFile()
        try {
            val file = root.resolve("data").apply { writeText("content") }

            assertThrows(IllegalStateException::class.java) {
                file.ensureDirectory()
            }

            assertEquals("content", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
