/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserDataManagerTest {

    @Test
    fun transientModelPreferencesAreExcluded() {
        assertTrue(isTransientSharedPreferenceFile("handwriting_recognition.xml"))
        assertTrue(isTransientSharedPreferenceFile("handwriting_recognition.xml.bak"))
        assertTrue(isTransientSharedPreferenceFile("com.google.mlkit.internal.xml"))
        assertTrue(
            isTransientSharedPreferenceFile(
                "gms_icing_mdd_org.fcitx.fcitx17.android_mlkit_digital_ink_recognition.xml"
            )
        )
        assertTrue(isTransientSharedPreferenceFile("gms_icing_mdd_migrations.xml"))
    }

    @Test
    fun userPreferencesRemainExportable() {
        assertFalse(isTransientSharedPreferenceFile("org.fcitx.fcitx17.android_preferences.xml"))
        assertFalse(isTransientSharedPreferenceFile("clipboard.xml"))
        assertFalse(isTransientSharedPreferenceFile("recently_used.xml"))
    }

    @Test
    fun preservesOnlyTransientSharedPreferencesDuringImport() {
        val root = Files.createTempDirectory("user-data-").toFile()
        try {
            val existing = root.resolve("existing").also(File::mkdir)
            val staged = root.resolve("staged").also(File::mkdir)
            existing.resolve("old.xml").writeText("stale preference")
            existing.resolve("handwriting_recognition.xml").writeText("local model state")
            staged.resolve("clipboard.xml").writeText("imported clipboard")

            preserveTransientSharedPreferenceFiles(existing, staged)

            assertFalse(staged.resolve("old.xml").exists())
            assertTrue(staged.resolve("clipboard.xml").readText() == "imported clipboard")
            assertTrue(
                staged.resolve("handwriting_recognition.xml").readText() == "local model state"
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requiresAllExportedUserDataDirectories() {
        val root = Files.createTempDirectory("user-data-").toFile()
        try {
            assertFalse(hasRequiredUserDataDirectories(root))

            root.resolve("shared_prefs").mkdir()
            root.resolve("databases").mkdir()
            assertFalse(hasRequiredUserDataDirectories(root))

            root.resolve("external").mkdir()
            assertTrue(hasRequiredUserDataDirectories(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
