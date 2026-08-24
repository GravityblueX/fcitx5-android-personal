/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
import kotlinx.serialization.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

interface DataDescriptorPluginExtension {
    /**
     * paths relative to asset dir to be excluded
     */
    val excludes: ListProperty<String>

    /**
     * symlinks to create after copying files
     * target -> source
     */
    val symlinks: MapProperty<String, String>
}

/**
 * Add task generateDataDescriptor
 */
class DataDescriptorPlugin : Plugin<Project> {

    companion object {
        const val TASK = "generateDataDescriptor"
        const val CLEAN_TASK = "cleanDataDescriptor"
        const val FILE_NAME = "descriptor.json"
    }

    override fun apply(target: Project) {
        val extension = target.extensions.create<DataDescriptorPluginExtension>(TASK)
        extension.excludes.convention(listOf())
        extension.symlinks.convention(mapOf())
        target.tasks.register<DataDescriptorTask>(TASK) {
            inputDir.set(target.assetsDir)
            outputFile.set(target.assetsDir.resolve(FILE_NAME))
            excludes.set(extension.excludes)
            symlinks.set(extension.symlinks)
        }
        target.tasks.register<Delete>(CLEAN_TASK) {
            delete(target.assetsDir.resolve(FILE_NAME))
        }.also {
            target.cleanTask.dependsOn(it)
        }
    }

    abstract class DataDescriptorTask : DefaultTask() {
        @Serializable
        data class DataDescriptor(
            val sha256: String,
            val files: Map<String, String>,
            val symlinks: Map<String, String> = mapOf()
        )

        @get:Incremental
        @get:PathSensitive(PathSensitivity.RELATIVE)
        @get:InputDirectory
        abstract val inputDir: DirectoryProperty

        @get:Input
        abstract val excludes: ListProperty<String>

        @get:Input
        abstract val symlinks: MapProperty<String, String>

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        private val file by lazy { outputFile.get().asFile }

        private fun serialize(files: Map<String, String>, symlinks: Map<String, String>) {
            val sortedFiles = files.toSortedMap()
            val sortedSymlinks = symlinks.toSortedMap()
            if (sortedSymlinks.keys.intersect(sortedFiles.keys).isNotEmpty())
                throw IllegalArgumentException("Symlink target cannot be path in files")
            val descriptor = DataDescriptor(
                descriptorSha256(sortedFiles, sortedSymlinks),
                sortedFiles,
                sortedSymlinks
            )
            file.writeText(json.encodeToString(descriptor))
        }

        private fun deserialize(): Map<String, String> =
            json.decodeFromString<DataDescriptor>(file.readText()).files

        companion object {
            private val lowercaseHexDigits = "0123456789abcdef".toCharArray()

            private fun ByteArray.toLowercaseHex(): String {
                val characters = CharArray(size * 2)
                forEachIndexed { index, byte ->
                    val value = byte.toInt() and 0xff
                    characters[index * 2] = lowercaseHexDigits[value ushr 4]
                    characters[index * 2 + 1] = lowercaseHexDigits[value and 0x0f]
                }
                return characters.concatToString()
            }

            private fun MessageDigest.updateInt(value: Int) {
                update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
            }

            private fun MessageDigest.updateLengthPrefixed(value: String) {
                val bytes = value.encodeToByteArray()
                updateInt(bytes.size)
                update(bytes)
            }

            private fun MessageDigest.updateEntries(entries: Map<String, String>) {
                updateInt(entries.size)
                entries.forEach { (key, value) ->
                    updateLengthPrefixed(key)
                    updateLengthPrefixed(value)
                }
            }

            private fun descriptorSha256(
                files: Map<String, String>,
                symlinks: Map<String, String>,
            ): String {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.updateLengthPrefixed("DataDescriptorIdentityV1")
                digest.updateEntries(files)
                digest.updateEntries(symlinks)
                return digest.digest().toLowercaseHex()
            }

            fun sha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        when {
                            count < 0 -> break
                            count > 0 -> digest.update(buffer, 0, count)
                            else -> {
                                val next = input.read()
                                if (next < 0) break
                                digest.update(next.toByte())
                            }
                        }
                    }
                }
                return digest.digest().toLowercaseHex()
            }
        }

        @TaskAction
        fun execute(inputChanges: InputChanges) {
            val map =
                file.exists()
                    .takeIf { it }
                    ?.runCatching {
                        deserialize()
                            // remove all old dirs
                            .filterValues { it.isNotBlank() }
                            .toMutableMap()
                    }
                    ?.getOrNull()
                    ?: mutableMapOf()

            fun File.toDescriptorPath(): String = path.replace(File.separatorChar, '/')

            fun File.allParents(): List<File> =
                if (parentFile == null || parentFile.toDescriptorPath() in map)
                    listOf()
                else
                    listOf(parentFile) + parentFile.allParents()
            val normalizedOutputFile = file.absoluteFile.normalize()
            inputChanges.getFileChanges(inputDir).forEach { change ->
                if (change.file.absoluteFile.normalize() == normalizedOutputFile)
                    return@forEach
                logger.log(LogLevel.DEBUG, "${change.changeType}: ${change.normalizedPath}")
                val relativeFile = change.file.relativeTo(file.parentFile)
                val key = relativeFile.toDescriptorPath()
                if (change.changeType == ChangeType.REMOVED || key in excludes.get()) {
                    map.remove(key)
                } else {
                    map[key] = sha256(change.file)
                }
            }
            // calculate dirs
            inputDir.asFileTree.forEach {
                it.relativeTo(file.parentFile).allParents().forEach { p ->
                    map[p.toDescriptorPath()] = ""
                }
            }
            serialize(map, symlinks.get())
        }
    }
}
