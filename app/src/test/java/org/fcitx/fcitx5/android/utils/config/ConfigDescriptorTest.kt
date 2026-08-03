/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils.config

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDescriptorTest {

    @Test
    fun emptyDescriptorDoesNotProduceTopLevelDefinition() {
        assertTrue(ConfigDescriptor.parseTopLevel(RawConfig()).fold({ true }, { false }))
    }

    @Test
    fun minimumTopLevelDescriptorParsesWithoutOptions() {
        val descriptor = ConfigDescriptor.parseTopLevel(
            RawConfig(arrayOf(RawConfig("General", emptyArray())))
        ).fold({ error -> throw AssertionError(error) }, { it })
        assertEquals("General", descriptor.name)
        assertTrue(descriptor.values.isEmpty())
    }
}
