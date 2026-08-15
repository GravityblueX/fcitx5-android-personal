/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BuiltinQuickPhraseTest {

    @Test
    fun deletesEnabledOverride() {
        assertDeletesOverride(disabled = false)
    }

    @Test
    fun deletesDisabledOverride() {
        assertDeletesOverride(disabled = true)
    }

    private fun assertDeletesOverride(disabled: Boolean) {
        val root = Files.createTempDirectory("builtin-quickphrase-").toFile()
        try {
            val builtinFile = root.resolve("builtin.mb").also { it.writeText("builtin") }
            val overrideFile = root.resolve("custom.mb")
            val actualOverrideFile = if (disabled) {
                root.resolve("custom.mb.disable")
            } else {
                overrideFile
            }.also { it.writeText("override") }
            val quickPhrase = BuiltinQuickPhrase(builtinFile, overrideFile)

            assertNotNull(quickPhrase.override)
            assertTrue(actualOverrideFile.exists())

            quickPhrase.deleteOverride()

            assertNull(quickPhrase.override)
            assertFalse(actualOverrideFile.exists())
            assertTrue(quickPhrase.isEnabled)
        } finally {
            root.deleteRecursively()
        }
    }
}
