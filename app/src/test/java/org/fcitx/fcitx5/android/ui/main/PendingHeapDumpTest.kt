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
    fun beginningDumpReturnsReplacedPendingFile() {
        val old = File("cache/old.hprof")
        val pending = PendingHeapDump(old.path)
        val replacement = File("cache/new.hprof")

        assertEquals(old, pending.begin(replacement))

        assertEquals(replacement, pending.file)
    }

    @Test
    fun beginningDumpTracksFileWhenIdle() {
        val pending = PendingHeapDump()
        val file = File("cache/new.hprof")

        assertNull(pending.begin(file))

        assertEquals(file, pending.file)
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
