/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPinyinDictionaryRouteImportTest {

    @Test
    fun restoresNonBlankRouteUri() {
        assertEquals(
            "content://provider/dictionary",
            PendingPinyinDictionaryRouteImport("content://provider/dictionary").uri,
        )
        assertNull(PendingPinyinDictionaryRouteImport("   ").uri)
    }

    @Test
    fun startsPendingImportOnlyOnceWhileRunning() {
        val pending = PendingPinyinDictionaryRouteImport("content://provider/dictionary")

        assertEquals("content://provider/dictionary", pending.start())
        assertNull(pending.start())
    }

    @Test
    fun retriesImportAfterCancellation() {
        val pending = PendingPinyinDictionaryRouteImport("content://provider/dictionary")

        pending.start()
        pending.finish(shouldRetry = true)

        assertEquals("content://provider/dictionary", pending.uri)
        assertEquals("content://provider/dictionary", pending.start())
    }

    @Test
    fun consumesImportAfterNormalCompletion() {
        val pending = PendingPinyinDictionaryRouteImport("content://provider/dictionary")

        pending.start()
        pending.finish(shouldRetry = false)

        assertNull(pending.uri)
        assertNull(pending.start())
    }
}
