/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
