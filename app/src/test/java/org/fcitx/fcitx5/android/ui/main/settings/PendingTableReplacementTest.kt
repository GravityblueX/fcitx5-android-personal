/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingTableReplacementTest {

    @Test
    fun restoresPendingConfigFileName() {
        assertEquals(
            "custom-table.conf",
            PendingTableReplacement("custom-table.conf").configFileName,
        )
    }

    @Test
    fun beginningReplacementUpdatesConfigFileName() {
        val pending = PendingTableReplacement("old-table.conf")

        pending.begin("new-table.conf")

        assertEquals("new-table.conf", pending.configFileName)
    }

    @Test
    fun consumingReplacementClearsConfigFileName() {
        val pending = PendingTableReplacement("custom-table.conf")

        assertEquals("custom-table.conf", pending.consume())
        assertNull(pending.configFileName)
        assertNull(pending.consume())
    }
}
