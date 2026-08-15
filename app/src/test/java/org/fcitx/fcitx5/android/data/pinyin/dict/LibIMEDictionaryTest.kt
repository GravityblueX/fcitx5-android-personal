/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
