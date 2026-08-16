/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibIMEDictionaryTest {

    @Test
    fun preservesEmbeddedDisableMarkerAcrossEnableCycle() {
        val root = Files.createTempDirectory("libime-dictionary-").toFile()
        try {
            val disabledName = "custom.dict.disable.name.dict.disable"
            val disabledFile = root.resolve(disabledName).also { it.writeText("dictionary") }
            val dictionary = LibIMEDictionary(disabledFile)

            assertFalse(dictionary.isEnabled)
            assertEquals("custom.dict.disable.name", dictionary.name)

            assertTrue(dictionary.enable())
            assertTrue(dictionary.isEnabled)
            assertEquals("custom.dict.disable.name.dict", dictionary.file.name)
            assertEquals("custom.dict.disable.name", dictionary.name)

            assertTrue(dictionary.disable())
            assertFalse(dictionary.isEnabled)
            assertEquals(disabledName, dictionary.file.name)
            assertEquals("custom.dict.disable.name", dictionary.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enablingDoesNotReplaceExistingFile() {
        val root = Files.createTempDirectory("libime-dictionary-").toFile()
        try {
            val disabledFile = ReplacingRenameFile(root.resolve("custom.dict.disable").path)
                .also { it.writeText("disabled content") }
            val enabledFile = root.resolve("custom.dict").also { it.writeText("enabled content") }
            val dictionary = LibIMEDictionary(disabledFile)

            assertFalse(dictionary.enable())
            assertFalse(dictionary.isEnabled)
            assertEquals(disabledFile, dictionary.file)
            assertEquals("disabled content", disabledFile.readText())
            assertEquals("enabled content", enabledFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun disablingDoesNotReplaceExistingFile() {
        val root = Files.createTempDirectory("libime-dictionary-").toFile()
        try {
            val enabledFile = ReplacingRenameFile(root.resolve("custom.dict").path)
                .also { it.writeText("enabled content") }
            val disabledFile =
                root.resolve("custom.dict.disable").also { it.writeText("disabled content") }
            val dictionary = LibIMEDictionary(enabledFile)

            assertFalse(dictionary.disable())
            assertTrue(dictionary.isEnabled)
            assertEquals(enabledFile, dictionary.file)
            assertEquals("enabled content", enabledFile.readText())
            assertEquals("disabled content", disabledFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private class ReplacingRenameFile(path: String) : File(path) {
        override fun renameTo(destination: File): Boolean {
            copyTo(destination, overwrite = true)
            return delete()
        }
    }
}
