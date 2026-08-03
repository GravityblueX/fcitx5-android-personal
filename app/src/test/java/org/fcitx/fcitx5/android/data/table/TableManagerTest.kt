/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
