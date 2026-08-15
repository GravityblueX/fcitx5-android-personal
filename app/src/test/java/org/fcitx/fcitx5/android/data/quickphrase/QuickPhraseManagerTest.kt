/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class QuickPhraseManagerTest {

    @Test
    fun normalizesImportedQuickPhraseNames() {
        val target = quickPhraseImportTarget("..\\..\\custom.name.mb")!!

        assertEquals("custom.name.mb", target.fileName)
        assertEquals("custom.name", target.entryName)
    }

    @Test
    fun rejectsInvalidImportedQuickPhraseNames() {
        listOf("quickphrase", ".mb", "   .mb", "..mb", "custom.mb.disable")
            .forEach { assertNull(quickPhraseImportTarget(it)) }
    }

    @Test
    fun rejectsDuplicateCreatedQuickPhrasesWithoutChangingExistingFile() {
        val directory = Files.createTempDirectory("quickphrase-").toFile()
        try {
            val file = reserveQuickPhraseFile(directory, "custom.mb")
            file.writeText("existing content")

            assertThrows(FileAlreadyExistsException::class.java) {
                reserveQuickPhraseFile(directory, "custom.mb")
            }

            assertTrue(file.isFile)
            assertEquals("existing content", file.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publishesImportedQuickPhraseOnlyAfterStagingCompletes() {
        val directory = Files.createTempDirectory("quickphrase-import-").toFile()
        try {
            val content = "shortcut phrase"

            val installed = installQuickPhraseFile(
                ByteArrayInputStream(content.toByteArray()),
                directory,
                "custom.mb",
            ) { staged, destination ->
                assertEquals(content, staged.readText())
                assertEquals("", destination.readText())
                publishForTest(staged, destination)
            }

            assertEquals("custom.mb", installed.name)
            assertEquals(content, installed.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansPartialQuickPhraseImportWhenCopyFails() {
        val directory = Files.createTempDirectory("quickphrase-import-").toFile()
        try {
            val stream = object : InputStream() {
                private var reads = 0

                override fun read(): Int {
                    if (reads++ >= 4) throw IOException("copy interrupted")
                    return 'x'.code
                }
            }

            assertThrows(IOException::class.java) {
                installQuickPhraseFile(stream, directory, "custom.mb", ::publishForTest)
            }

            assertTrue(directory.listFiles()?.isEmpty() == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleansReservedQuickPhraseWhenPublishingFails() {
        val directory = Files.createTempDirectory("quickphrase-import-").toFile()
        try {
            assertThrows(IOException::class.java) {
                installQuickPhraseFile(
                    ByteArrayInputStream("complete content".toByteArray()),
                    directory,
                    "custom.mb",
                ) { staged, destination ->
                    assertEquals("complete content", staged.readText())
                    assertTrue(destination.isFile)
                    throw IOException("publish interrupted")
                }
            }

            assertTrue(directory.listFiles()?.isEmpty() == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesExistingQuickPhraseWhenImportedNameAlreadyExists() {
        val directory = Files.createTempDirectory("quickphrase-import-").toFile()
        try {
            val existing = directory.resolve("custom.mb").apply { writeText("existing content") }
            var publishCalled = false

            assertThrows(FileAlreadyExistsException::class.java) {
                installQuickPhraseFile(
                    ByteArrayInputStream("replacement".toByteArray()),
                    directory,
                    "custom.mb",
                ) { _, _ -> publishCalled = true }
            }

            assertFalse(publishCalled)
            assertEquals("existing content", existing.readText())
            assertEquals(listOf("custom.mb"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun publishForTest(staged: File, destination: File) {
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
