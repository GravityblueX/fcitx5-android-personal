/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcitxRawConfigStoreTest {

    private fun store() = FcitxRawConfigStore(
        RawConfig(
            arrayOf(
                RawConfig("enabled", "True"),
                RawConfig("count", "invalid"),
                RawConfig("label", "value")
            )
        )
    )

    @Test
    fun readsConfiguredValues() {
        val store = store()
        assertTrue(store.getBoolean("enabled", false))
        assertEquals("value", store.getString("label", null))
    }

    @Test
    fun missingOrMalformedValuesUseDefaults() {
        val store = store()
        assertFalse(store.getBoolean("missing", false))
        assertEquals(42, store.getInt("count", 42))
        assertNull(store.getString("missing", null))
    }

    @Test
    fun writesOnlyUpdateExistingValues() {
        val store = store()
        store.putInt("count", 7)
        store.putString("missing", "ignored")
        assertEquals(7, store.getInt("count", 0))
        assertEquals("fallback", store.getString("missing", "fallback"))
    }
}
