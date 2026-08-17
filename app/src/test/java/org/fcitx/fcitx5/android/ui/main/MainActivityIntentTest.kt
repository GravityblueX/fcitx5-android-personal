/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MainActivityIntentTest {

    @Test
    fun readsSettingsRoutesFromInternalAlias() {
        val route = Any()

        assertSame(
            route,
            readInternalSettingsRoute(
                Intent.ACTION_RUN,
                INTERNAL_MAIN_ACTIVITY_ALIAS,
            ) { route },
        )
    }

    @Test
    fun rejectsExportedActivityBeforeReadingRoute() {
        var routeRead = false

        assertNull(
            readInternalSettingsRoute(
                Intent.ACTION_RUN,
                MainActivity::class.java.name,
            ) {
                routeRead = true
                Any()
            }
        )
        assertFalse(routeRead)
    }

    @Test
    fun rejectsOtherActionsBeforeReadingRoute() {
        var routeRead = false

        assertNull(
            readInternalSettingsRoute(
                Intent.ACTION_VIEW,
                INTERNAL_MAIN_ACTIVITY_ALIAS,
            ) {
                routeRead = true
                Any()
            }
        )
        assertFalse(routeRead)
    }

    @Test
    fun rejectsMissingComponentBeforeReadingRoute() {
        var routeRead = false

        assertNull(
            readInternalSettingsRoute(Intent.ACTION_RUN, null) {
                routeRead = true
                Any()
            }
        )
        assertFalse(routeRead)
    }
}
