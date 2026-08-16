/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TableManagerTest {

    @Test
    fun reservesDistinctTableDictionaryNames() {
        val directory = Files.createTempDirectory("table-dict-").toFile()
        try {
            directory.resolve("sample.main.dict").writeText("existing")

            val reserved = reserveTableFile(directory, "sample.main.dict")

            assertEquals("sample.main (2).dict", reserved.name)
            assertTrue(reserved.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansEveryPublishedFileAfterFailedImport() {
        val directory = Files.createTempDirectory("table-import-").toFile()
        try {
            val configuration = directory.resolve("sample.conf").also { it.writeText("config") }
            val dictionary = directory.resolve("sample.dict").also { it.writeText("dictionary") }

            val results = cleanupTableImportFiles(configuration, dictionary)

            assertTrue(results.all(Result<Unit>::isSuccess))
            assertFalse(configuration.exists())
            assertFalse(dictionary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun continuesCleanupAfterOnePublishedFileFails() {
        val directory = Files.createTempDirectory("table-import-").toFile()
        try {
            val configuration = UndeletableFile(directory.resolve("sample.conf").path)
                .also { it.writeText("config") }
            val dictionary = directory.resolve("sample.dict").also { it.writeText("dictionary") }

            val results = cleanupTableImportFiles(configuration, dictionary)

            assertTrue(results.first().isFailure)
            assertTrue(results.last().isSuccess)
            assertTrue(configuration.exists())
            assertFalse(dictionary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class UndeletableFile(path: String) : File(path) {
        override fun delete(): Boolean = false
    }
}
