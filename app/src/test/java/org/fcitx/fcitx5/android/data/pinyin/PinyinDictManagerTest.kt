/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinyinDictManagerTest {

    @Test
    fun identifiesOnlyPinyinImportStagingFiles() {
        assertTrue(isPinyinImportStagingFile(".pinyin-import-123.staged"))
        assertFalse(isPinyinImportStagingFile("pinyin-import-123.staged"))
        assertFalse(isPinyinImportStagingFile(".pinyin-import-123.dict"))
    }

    @Test
    fun normalizesImportedDictionaryNames() {
        val regular = pinyinDictionaryImportTarget("../../custom.name.txt")!!
        assertEquals("custom.name.txt", regular.sourceFileName)
        assertEquals("custom.name", regular.entryName)
        assertEquals("custom.name.dict", regular.destinationFileName)

        val disabled = pinyinDictionaryImportTarget("custom.dict.disable")!!
        assertEquals("custom", disabled.entryName)
        assertEquals("custom.dict", disabled.destinationFileName)
    }

    @Test
    fun rejectsInvalidImportedDictionaryNames() {
        listOf("dictionary", ".dict", ".dict.disable", "   .txt", "...dict")
            .forEach { assertNull(pinyinDictionaryImportTarget(it)) }
    }

    @Test
    fun collectsMergedProcessOutput() {
        val operatingSystemName = System.getProperty("os.name").orEmpty()
        val executableName = if (operatingSystemName.startsWith("Windows", true)) {
            "java.exe"
        } else {
            "java"
        }
        val javaExecutable = File(System.getProperty("java.home"), "bin/${executableName}")
        val process = ProcessBuilder(javaExecutable.path, "-version")
            .redirectErrorStream(true)
            .start()

        val (exitCode, output) = collectProcessOutput(process)

        assertEquals(0, exitCode)
        assertTrue(output.isNotBlank())
    }
}
