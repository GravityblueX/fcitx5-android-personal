/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomSoundUriPermissionTest {

    @Test
    fun firstImportKeepsNoPreviousPermission() {
        assertNull(previousCustomSoundUriToRelease("", "content://provider/new"))
    }

    @Test
    fun replacingSoundReleasesPreviousPermission() {
        assertEquals(
            "content://provider/previous",
            previousCustomSoundUriToRelease(
                "content://provider/previous",
                "content://provider/next"
            )
        )
    }

    @Test
    fun selectingSameSoundKeepsItsPermission() {
        assertNull(
            previousCustomSoundUriToRelease(
                "content://provider/sound",
                "content://provider/sound"
            )
        )
    }

    @Test
    fun resetReleasesConfiguredSoundPermission() {
        assertEquals(
            "content://provider/sound",
            previousCustomSoundUriToRelease("content://provider/sound", "")
        )
    }
}
