/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PinyinDictManagerTest {

    @Test
    fun serializesPinyinDictionaryOperations() {
        val workerCount = 8
        val barrier = CyclicBarrier(workerCount)
        val activeOperations = AtomicInteger()
        val maximumActiveOperations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val operations = List(workerCount) {
                Callable {
                    barrier.await(5, TimeUnit.SECONDS)
                    runPinyinDictionaryOperation {
                        val active = activeOperations.incrementAndGet()
                        try {
                            maximumActiveOperations.updateAndGet { maximum ->
                                maxOf(maximum, active)
                            }
                            Thread.sleep(25)
                        } finally {
                            activeOperations.decrementAndGet()
                        }
                    }
                }
            }

            executor.invokeAll(operations).forEach { future ->
                future.get(5, TimeUnit.SECONDS)
            }

            assertEquals(1, maximumActiveOperations.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun identifiesOnlyPinyinImportStagingFiles() {
        assertTrue(isPinyinImportStagingFile(".pinyin-import-123.staged"))
        assertFalse(isPinyinImportStagingFile("pinyin-import-123.staged"))
        assertFalse(isPinyinImportStagingFile(".pinyin-import-123.dict"))
    }

    @Test
    fun normalizesImportedDictionaryNames() {
        val regular = pinyinDictionaryImportTarget("../../custom.name.txt")!!
        assertEquals("custom.name.txt", regular.sourceFileName)
        assertEquals("custom.name", regular.entryName)
        assertEquals("custom.name.dict", regular.destinationFileName)

        val disabled = pinyinDictionaryImportTarget("custom.dict.disable")!!
        assertEquals("custom", disabled.entryName)
        assertEquals("custom.dict", disabled.destinationFileName)
    }

    @Test
    fun rejectsInvalidImportedDictionaryNames() {
        listOf("dictionary", ".dict", ".dict.disable", "   .txt", "...dict")
            .forEach { assertNull(pinyinDictionaryImportTarget(it)) }
    }

    @Test
    fun detectsEnabledDisabledAndBuiltinNameConflicts() {
        val root = Files.createTempDirectory("pinyin-conflict-").toFile()
        try {
            val userDirectory = root.resolve("user").also(File::mkdir)
            val builtinDirectory = root.resolve("builtin").also(File::mkdir)

            assertFalse(pinyinDictionaryEntryExists(userDirectory, builtinDirectory, "custom"))

            val enabled = userDirectory.resolve("custom.dict").also { it.writeText("enabled") }
            assertTrue(pinyinDictionaryEntryExists(userDirectory, builtinDirectory, "custom"))
            enabled.delete()

            val disabled = userDirectory.resolve("custom.dict.disable")
                .also { it.writeText("disabled") }
            assertTrue(pinyinDictionaryEntryExists(userDirectory, builtinDirectory, "custom"))
            disabled.delete()

            builtinDirectory.resolve("custom.dict").writeText("builtin")
            assertTrue(pinyinDictionaryEntryExists(userDirectory, builtinDirectory, "custom"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsOnlyManagedPinyinDictionaryPaths() {
        val root = Files.createTempDirectory("pinyin-managed-").toFile()
        try {
            val directory = root.resolve("managed").also(File::mkdir)
            val managed = directory.resolve("custom.dict").also { it.writeText("dictionary") }
            val unmanaged = root.resolve("custom.dict").also { it.writeText("dictionary") }

            assertEquals(managed.canonicalFile, managedPinyinDictionaryFile(directory, managed))
            assertThrows(IllegalStateException::class.java) {
                managedPinyinDictionaryFile(directory, unmanaged)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun collectsMergedProcessOutput() {
        val operatingSystemName = System.getProperty("os.name").orEmpty()
        val executableName = if (operatingSystemName.startsWith("Windows", true)) {
            "java.exe"
        } else {
            "java"
        }
        val javaExecutable = File(System.getProperty("java.home"), "bin/${executableName}")
        val process = ProcessBuilder(javaExecutable.path, "-version")
            .redirectErrorStream(true)
            .start()

        val (exitCode, output) = collectProcessOutput(process)

        assertEquals(0, exitCode)
        assertTrue(output.isNotBlank())
    }
}
