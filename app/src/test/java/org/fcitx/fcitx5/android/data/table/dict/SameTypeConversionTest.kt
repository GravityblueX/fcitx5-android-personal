package org.fcitx.fcitx5.android.data.table.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SameTypeConversionTest {

    @Test
    fun textConversionToSamePathPreservesSource() {
        assertSamePathConversionPreservesSource("custom.txt") { source, destination ->
            TextDictionary(source).toTextDictionary(destination)
        }
    }

    @Test
    fun libIMEConversionToSamePathPreservesSource() {
        assertSamePathConversionPreservesSource("custom.dict") { source, destination ->
            LibIMEDictionary(source).toLibIMEDictionary(destination)
        }
    }

    @Test
    fun failedWritesPreserveExistingDestinations() {
        val root = Files.createTempDirectory("table-dictionary-").toFile()
        try {
            val source = root.resolve("source.txt").also { it.writeText("source") }
            val textDestination = root.resolve("target.txt").also { it.writeText("text") }
            val binDestination = root.resolve("target.dict").also { it.writeText("binary") }
            val dictionary = FailingDictionary(source)

            assertThrows(IOException::class.java) {
                dictionary.failTextWrite(textDestination)
            }
            assertThrows(IOException::class.java) {
                dictionary.failBinWrite(binDestination)
            }

            assertEquals("text", textDestination.readText())
            assertEquals("binary", binDestination.readText())
            assertEquals(
                listOf("source.txt", "target.dict", "target.txt"),
                root.list()?.sorted()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertSamePathConversionPreservesSource(
        fileName: String,
        convert: (File, File) -> Dictionary,
    ) {
        val root = Files.createTempDirectory("table-dictionary-").toFile()
        try {
            val source = root.resolve(fileName).also { it.writeText("dictionary") }
            val destination = root.resolve(".").resolve(fileName)

            val converted = convert(source, destination)

            assertTrue(source.exists())
            assertEquals("dictionary", source.readText())
            assertEquals(source.canonicalFile, converted.file.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private class FailingDictionary(override val file: File) : Dictionary() {
        override val type: Type = Type.Text

        override fun toTextDictionary(dest: File): TextDictionary = error("Not used")

        override fun toLibIMEDictionary(dest: File): LibIMEDictionary = error("Not used")

        fun failTextWrite(dest: File) {
            writeTxtAtomically(dest, ::writeThenFail)
        }

        fun failBinWrite(dest: File) {
            writeBinAtomically(dest, ::writeThenFail)
        }

        private fun writeThenFail(staged: File) {
            staged.writeText("partial")
            throw IOException("conversion failed")
        }
    }
}
