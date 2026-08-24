/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildMetadataTaskTest {

    @get:Rule
    val projectDirectory = TemporaryFolder()

    private fun runMetadata(
        versionName: String,
        commitHash: String,
        timestamp: String,
    ) = GradleRunner.create()
        .withProjectDir(projectDirectory.root)
        .withPluginClasspath()
        .withArguments(
            "generateMetadata",
            "-PbuildVersionName=$versionName",
            "-PbuildCommitHash=$commitHash",
            "-PbuildTimestamp=$timestamp",
            "--stacktrace",
        )
        .build()

    @Test
    fun rerunsWhenMetadataInputsChange() {
        projectDirectory.newFile("settings.gradle").writeText(
            "rootProject.name = 'build-metadata-test'"
        )
        projectDirectory.newFile("build.gradle").writeText(
            """
            plugins {
                id 'base'
                id 'org.fcitx.fcitx5.android.data-descriptor'
            }

            def metadataTask = Class.forName('BuildMetadataPlugin${'$'}BuildMetadataTask')
            tasks.register('generateMetadata', metadataTask) { task ->
                task.outputFile.set(layout.buildDirectory.file('metadata.json'))
                if (task.metaClass.hasProperty(task, 'versionName')) {
                    task.versionName.set(providers.gradleProperty('buildVersionName'))
                    task.commitHash.set(providers.gradleProperty('buildCommitHash'))
                    task.timestamp.set(providers.gradleProperty('buildTimestamp'))
                }
            }
            """.trimIndent()
        )

        assertEquals(
            TaskOutcome.SUCCESS,
            runMetadata("1.0", "first", "1000").task(":generateMetadata")?.outcome,
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            runMetadata("2.0", "second", "2000").task(":generateMetadata")?.outcome,
        )
        val generated = json.decodeFromString<BuildMetadataPlugin.BuildMetadataTask.BuildMetadata>(
            projectDirectory.root.resolve("build/metadata.json").readText()
        )
        assertEquals(
            BuildMetadataPlugin.BuildMetadataTask.BuildMetadata("2.0", "second", "2000"),
            generated,
        )
        assertEquals(
            TaskOutcome.UP_TO_DATE,
            runMetadata("2.0", "second", "2000").task(":generateMetadata")?.outcome,
        )
    }
}
