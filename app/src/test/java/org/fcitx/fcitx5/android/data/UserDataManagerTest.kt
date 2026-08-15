/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    @Test
    fun recognizesFilesInsideExportRoot() {
        val root = Files.createTempDirectory("user-data-export-").toFile()
        try {
            val source = root.resolve("source").also(File::mkdir)
            val nested = source.resolve("nested/file.txt").also { file ->
                file.parentFile?.mkdirs()
                file.writeText("data")
            }

            assertTrue(isSafeUserDataExportPath(source, source))
            assertTrue(isSafeUserDataExportPath(nested, source))
            assertFalse(isSafeUserDataExportPath(root.resolve("outside.txt"), source))
            val linkedFile = object : File(source, "linked.txt") {
                override fun getCanonicalFile(): File = root.resolve("outside.txt")
            }
            assertFalse(isSafeUserDataExportPath(linkedFile, source))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportedFileTreeSkipsSymbolicLinks() {
        val root = Files.createTempDirectory("user-data-export-").toFile()
        val linkedFile = root.resolve("source/linked.txt")
        val linkedDirectory = root.resolve("source/linked-directory")
        try {
            val source = root.resolve("source").also(File::mkdir)
            source.resolve("normal.txt").writeText("exported")
            val outsideFile = root.resolve("outside.txt").also { it.writeText("private") }
            val outsideDirectory = root.resolve("outside-directory").also(File::mkdir)
            outsideDirectory.resolve("private.txt").writeText("private")
            try {
                Files.createSymbolicLink(linkedFile.toPath(), outsideFile.toPath())
                Files.createSymbolicLink(linkedDirectory.toPath(), outsideDirectory.toPath())
            } catch (e: Exception) {
                assumeNoException(e)
            }

            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { output ->
                    writeUserDataFileTree(source, "external", output)
                }
            }.toByteArray()
            val entryNames = ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                buildList {
                    var entry = input.nextEntry
                    while (entry != null) {
                        add(entry.name)
                        input.closeEntry()
                        entry = input.nextEntry
                    }
                }
            }

            assertEquals(listOf("external/", "external/normal.txt"), entryNames)
        } finally {
            Files.deleteIfExists(linkedFile.toPath())
            Files.deleteIfExists(linkedDirectory.toPath())
            root.deleteRecursively()
        }
    }
}
