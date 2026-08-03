/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LogcatTest {

    @Test
    fun buildsCommandWithPidFilter() {
        assertArrayEquals(
            arrayOf("logcat", "--pid=123", "-d"),
            logcatCommand(123, "-d")
        )
    }

    @Test
    fun omitsPidFilterWhenPidIsNotSpecified() {
        assertArrayEquals(
            arrayOf("logcat", "-v", "brief"),
            logcatCommand(null, "-v", "brief")
        )
    }
}
