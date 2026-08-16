/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentlyUsedTest {

    @Test
    fun keepsOnlyLatestUniqueNonblankItems() {
        assertEquals(
            listOf("second", "first"),
            normalizeRecentlyUsed(listOf("first", "", "second", "first"), 2)
        )
    }

    @Test
    fun requiresPositiveLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeRecentlyUsed(emptyList(), 0)
        }
    }

    @Test
    fun removesMigratedFileAndEmptyDirectory() {
        val directory = Files.createTempDirectory("recently-used-").toFile()
        try {
            val file = directory.resolve("emoji").also { it.writeText("recent") }

            val results = cleanupMigratedRecentlyUsed(file, directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(file.exists())
            assertFalse(directory.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesDirectoryContainingOtherMigrationFiles() {
        val directory = Files.createTempDirectory("recently-used-").toFile()
        try {
            val file = directory.resolve("emoji").also { it.writeText("recent") }
            val other = directory.resolve("symbols").also { it.writeText("other") }

            val results = cleanupMigratedRecentlyUsed(file, directory)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(file.exists())
            assertTrue(other.exists())
            assertTrue(directory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesDirectoryWhenMigratedFileCleanupFails() {
        val directory = Files.createTempDirectory("recently-used-").toFile()
        try {
            val file = UndeletableFile(directory.resolve("emoji").path)
                .also { it.writeText("recent") }

            val results = cleanupMigratedRecentlyUsed(file, directory)

            assertEquals(1, results.size)
            assertTrue(results.single().isFailure)
            assertTrue(file.exists())
            assertTrue(directory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reportsEmptyDirectoryCleanupFailure() {
        val root = Files.createTempDirectory("recently-used-").toFile()
        try {
            val directory = UndeletableFile(root.resolve("legacy").path).also(File::mkdir)
            val file = directory.resolve("emoji").also { it.writeText("recent") }

            val results = cleanupMigratedRecentlyUsed(file, directory)

            assertEquals(2, results.size)
            assertTrue(results.first().isSuccess)
            assertTrue(results.last().isFailure)
            assertFalse(file.exists())
            assertTrue(directory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private class UndeletableFile(path: String) : File(path) {
        override fun delete(): Boolean = false
    }
}
