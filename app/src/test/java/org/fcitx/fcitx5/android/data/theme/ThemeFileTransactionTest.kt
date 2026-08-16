/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.utils.runWithRollback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ThemeFileTransactionTest {

    @Test
    fun identifiesOnlyLegacyThemeMetadataStagingFiles() {
        assertTrue(isLegacyThemeMetadataStagingFile("theme-123.staged"))
        assertFalse(isLegacyThemeMetadataStagingFile("file-install-123.staged"))
        assertFalse(isLegacyThemeMetadataStagingFile("theme-123.json"))
    }

    @Test
    fun cleansOnlyLegacyThemeMetadataStagingFiles() {
        val directory = Files.createTempDirectory("theme-metadata-cleanup-").toFile()
        try {
            val staged = directory.resolve("theme-123.staged").apply { writeText("partial") }
            val metadata = directory.resolve("theme-123.json").apply { writeText("keep") }
            val otherStaging = directory.resolve("file-install-123.staged").apply {
                writeText("keep")
            }
            val matchingDirectory = directory.resolve("theme-dir.staged").apply { mkdir() }

            cleanupLegacyThemeMetadataStaging(directory)

            assertFalse(staged.exists())
            assertTrue(metadata.isFile)
            assertTrue(otherStaging.isFile)
            assertTrue(matchingDirectory.isDirectory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesPrimaryFailureAndRecordsEveryRollbackFailure() {
        val primary = IllegalStateException("primary")
        val firstRollback = IllegalStateException("first rollback")
        val secondRollback = IllegalStateException("second rollback")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runWithRollback(
                rollback = {
                    listOf(
                        Result.failure(firstRollback),
                        Result.success(Unit),
                        Result.failure(secondRollback),
                    )
                },
            ) {
                throw primary
            }
        }

        assertSame(primary, thrown)
        assertArrayEquals(arrayOf(firstRollback, secondRollback), primary.suppressed)
    }

    @Test
    fun preservesPrimaryFailureWhenRollbackItselfThrows() {
        val primary = IllegalStateException("primary")
        val rollback = IllegalStateException("rollback")

        val thrown = assertThrows(IllegalStateException::class.java) {
            runWithRollback(
                rollback = { throw rollback },
            ) {
                throw primary
            }
        }

        assertSame(primary, thrown)
        assertArrayEquals(arrayOf(rollback), primary.suppressed)
    }
}
