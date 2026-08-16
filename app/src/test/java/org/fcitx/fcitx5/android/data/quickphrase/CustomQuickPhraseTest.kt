/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomQuickPhraseTest {

    @Test
    fun preservesEmbeddedDisableMarkerAcrossEnableCycle() {
        val root = Files.createTempDirectory("custom-quickphrase-").toFile()
        try {
            val disabledName = "custom.mb.disable.name.mb.disable"
            val disabledFile = root.resolve(disabledName).also { it.writeText("quickphrase") }
            val quickPhrase = CustomQuickPhrase(disabledFile)

            assertFalse(quickPhrase.isEnabled)
            assertEquals("custom.mb.disable.name", quickPhrase.name)

            assertTrue(quickPhrase.enable())
            assertTrue(quickPhrase.isEnabled)
            assertEquals("custom.mb.disable.name.mb", quickPhrase.file.name)
            assertEquals("custom.mb.disable.name", quickPhrase.name)

            assertTrue(quickPhrase.disable())
            assertFalse(quickPhrase.isEnabled)
            assertEquals(disabledName, quickPhrase.file.name)
            assertEquals("custom.mb.disable.name", quickPhrase.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enablingDoesNotReplaceExistingFile() {
        val root = Files.createTempDirectory("custom-quickphrase-").toFile()
        try {
            val disabledFile = ReplacingRenameFile(root.resolve("custom.mb.disable").path)
                .also { it.writeText("disabled content") }
            val enabledFile = root.resolve("custom.mb").also { it.writeText("enabled content") }
            val quickPhrase = CustomQuickPhrase(disabledFile)

            assertFalse(quickPhrase.enable())
            assertFalse(quickPhrase.isEnabled)
            assertEquals(disabledFile, quickPhrase.file)
            assertEquals("disabled content", disabledFile.readText())
            assertEquals("enabled content", enabledFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun disablingDoesNotReplaceExistingFile() {
        val root = Files.createTempDirectory("custom-quickphrase-").toFile()
        try {
            val enabledFile = ReplacingRenameFile(root.resolve("custom.mb").path)
                .also { it.writeText("enabled content") }
            val disabledFile =
                root.resolve("custom.mb.disable").also { it.writeText("disabled content") }
            val quickPhrase = CustomQuickPhrase(enabledFile)

            assertFalse(quickPhrase.disable())
            assertTrue(quickPhrase.isEnabled)
            assertEquals(enabledFile, quickPhrase.file)
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
