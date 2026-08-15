/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableFilesSelectionStateTest {

    @Test
    fun restoresSelectedFiles() {
        val state = TableFilesSelectionState(
            confUri = "content://table/config",
            confFileName = "table.conf",
            dictUri = "content://table/dictionary",
            dictFileName = "table.main.dict",
        )

        assertEquals("content://table/config", state.confUri)
        assertEquals("table.conf", state.confFileName)
        assertEquals("content://table/dictionary", state.dictUri)
        assertEquals("table.main.dict", state.dictFileName)
        assertTrue(state.hasSelection)
        assertTrue(state.isComplete)
    }

    @Test
    fun selectionBecomesCompleteAfterBothFiles() {
        val state = TableFilesSelectionState()

        state.selectConf("content://table/config", "table.conf")

        assertTrue(state.hasSelection)
        assertFalse(state.isComplete)

        state.selectDict("content://table/dictionary", "table.main.dict")

        assertTrue(state.isComplete)
    }

    @Test
    fun clearingSelectionRemovesBothFiles() {
        val state = TableFilesSelectionState(
            confUri = "content://table/config",
            confFileName = "table.conf",
            dictUri = "content://table/dictionary",
            dictFileName = "table.main.dict",
        )

        state.clear()

        assertNull(state.confUri)
        assertNull(state.confFileName)
        assertNull(state.dictUri)
        assertNull(state.dictFileName)
        assertFalse(state.hasSelection)
        assertFalse(state.isComplete)
    }
}
