/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

class LogViewTest {

    @Test
    fun mapsLogPrioritiesToColors() {
        assertEquals(R.attr.colorLogVerbose, logLineColorAttribute("V/Tag: message"))
        assertEquals(R.attr.colorLogDebug, logLineColorAttribute("D/Tag: message"))
        assertEquals(R.attr.colorLogInfo, logLineColorAttribute("I/Tag: message"))
        assertEquals(R.attr.colorLogWarning, logLineColorAttribute("W/Tag: message"))
        assertEquals(R.attr.colorLogError, logLineColorAttribute("E/Tag: message"))
        assertEquals(R.attr.colorLogFatal, logLineColorAttribute("F/Tag: message"))
    }

    @Test
    fun emptyLogLineUsesDefaultColor() {
        assertEquals(android.R.attr.colorForeground, logLineColorAttribute(""))
    }
}
