/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentResolverTest {

    @Test
    fun fileNameQueryReturnsProviderResult() {
        assertEquals("sound.wav", queryFileNameSafely { "sound.wav" })
    }

    @Test
    fun fileNameQueryReturnsNullWhenPermissionIsRevoked() {
        assertNull(queryFileNameSafely { throw SecurityException("Permission revoked") })
    }
}
