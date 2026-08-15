/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingThemeExportTest {

    @Test
    fun restoresPendingThemeName() {
        assertEquals("custom-theme", PendingThemeExport("custom-theme").themeName)
    }

    @Test
    fun beginningExportReplacesPendingThemeName() {
        val pending = PendingThemeExport("old-theme")

        pending.begin("new-theme")

        assertEquals("new-theme", pending.themeName)
    }

    @Test
    fun consumingExportClearsPendingThemeName() {
        val pending = PendingThemeExport("custom-theme")

        assertEquals("custom-theme", pending.consume())
        assertNull(pending.themeName)
        assertNull(pending.consume())
    }
}
