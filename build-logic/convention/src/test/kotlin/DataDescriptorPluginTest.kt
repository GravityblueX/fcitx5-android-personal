/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataDescriptorPluginTest {

    @get:Rule
    val projectDirectory = TemporaryFolder()

    private fun runDescriptorTask() = GradleRunner.create()
        .withProjectDir(projectDirectory.root)
        .withPluginClasspath()
        .withArguments(DataDescriptorPlugin.TASK, "--stacktrace")
        .build()

    private fun setUpProject() {
        projectDirectory.newFile("settings.gradle").writeText(
            "rootProject.name = 'data-descriptor-test'"
        )
        projectDirectory.newFile("build.gradle").writeText(
            """
            plugins {
                id 'base'
                id 'org.fcitx.fcitx5.android.data-descriptor'
            }
            """.trimIndent()
        )
    }

    @Test
    fun tracksRelativePathWhenSameNamedAssetMoves() {
        setUpProject()
        val assets = projectDirectory.newFolder("src", "main", "assets")
        val original = assets.resolve("first/shared.conf").apply {
            parentFile.mkdirs()
            writeText("unchanged content")
        }
        val descriptor = assets.resolve(DataDescriptorPlugin.FILE_NAME)

        runDescriptorTask()
        runDescriptorTask()
        assertEquals(
            TaskOutcome.UP_TO_DATE,
            runDescriptorTask().task(":${DataDescriptorPlugin.TASK}")?.outcome,
        )

        val moved = assets.resolve("second/shared.conf")
        moved.parentFile.mkdirs()
        assertTrue(original.renameTo(moved))
        original.parentFile.delete()

        val movedResult = runDescriptorTask()

        val generated = descriptor.readText()
        assertEquals(
            TaskOutcome.SUCCESS,
            movedResult.task(":${DataDescriptorPlugin.TASK}")?.outcome,
        )
        assertFalse(generated.contains("first/shared.conf"))
        assertTrue(generated.contains("second/shared.conf"))
        assertFalse(original.exists())
        assertTrue(moved.exists())
    }

    @Test
    fun usesPortableSeparatorsForDirectoryEntries() {
        setUpProject()
        val assets = projectDirectory.newFolder("src", "main", "assets")
        assets.resolve("nested/child/file.conf").apply {
            parentFile.mkdirs()
            writeText("content")
        }

        runDescriptorTask()

        val generated = json.decodeFromString<DataDescriptorPlugin.DataDescriptorTask.DataDescriptor>(
            assets.resolve(DataDescriptorPlugin.FILE_NAME).readText()
        )
        assertTrue("nested/child" in generated.files)
        assertFalse(generated.files.keys.any { '\\' in it })
    }
}
