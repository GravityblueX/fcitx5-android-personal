/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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

}
