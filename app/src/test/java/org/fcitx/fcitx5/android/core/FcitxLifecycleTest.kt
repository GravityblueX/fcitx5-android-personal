/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FcitxLifecycleTest {

    @Test
    fun startupFailureReturnsLifecycleToStopped() {
        val lifecycle = FcitxLifecycleRegistry()

        lifecycle.postEvent(FcitxLifecycle.Event.ON_START)
        lifecycle.postEvent(FcitxLifecycle.Event.ON_START_FAILED)

        assertEquals(FcitxLifecycle.State.STOPPED, lifecycle.currentState)
    }
}
