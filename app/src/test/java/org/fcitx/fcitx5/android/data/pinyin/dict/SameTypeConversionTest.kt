package org.fcitx.fcitx5.android.data.pinyin.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    private fun assertSamePathConversionPreservesSource(
        fileName: String,
        convert: (File, File) -> PinyinDictionary,
    ) {
        val root = Files.createTempDirectory("pinyin-dictionary-").toFile()
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
}
