/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PendingHeapDumpTest {

    @Test
    fun restoresPendingFilePath() {
        val pending = PendingHeapDump("cache/restored.hprof")

        assertEquals(File("cache/restored.hprof"), pending.file)
        assertEquals("cache${File.separator}restored.hprof", pending.path)
    }

    @Test
    fun beginningDumpReplacesPendingFile() {
        val pending = PendingHeapDump("cache/old.hprof")
        val replacement = File("cache/new.hprof")

        pending.begin(replacement)

        assertEquals(replacement, pending.file)
    }

    @Test
    fun consumingDumpClearsPendingFile() {
        val file = File("cache/pending.hprof")
        val pending = PendingHeapDump(file.path)

        assertEquals(file, pending.consume())
        assertNull(pending.file)
        assertNull(pending.path)
        assertNull(pending.consume())
    }
}
