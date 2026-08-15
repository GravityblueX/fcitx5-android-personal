/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPinyinDictionaryImportTest {

    @Test
    fun restoresPendingUri() {
        assertEquals(
            "content://provider/dictionary",
            PendingPinyinDictionaryImport("content://provider/dictionary").uri
        )
    }

    @Test
    fun beginningImportReplacesPendingUri() {
        val pending = PendingPinyinDictionaryImport("content://provider/old")

        pending.begin("content://provider/new")

        assertEquals("content://provider/new", pending.uri)
    }

    @Test
    fun consumingImportClearsPendingUri() {
        val pending = PendingPinyinDictionaryImport("content://provider/dictionary")

        assertEquals("content://provider/dictionary", pending.consume())
        assertNull(pending.uri)
        assertNull(pending.consume())
    }

    @Test
    fun clearingImportDropsPendingUri() {
        val pending = PendingPinyinDictionaryImport("content://provider/dictionary")

        pending.clear()

        assertNull(pending.uri)
    }
}
