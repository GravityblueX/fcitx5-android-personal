package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class DataAssetTest {

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Test
    fun copiesAndVerifiesAssetAtLimit() {
        val content = "verified asset".encodeToByteArray()
        val output = ByteArrayOutputStream()

        val copiedBytes = ByteArrayInputStream(content).copyManagedDataAsset(
            output,
            content.sha256(),
            content.size.toLong(),
        )

        assertEquals(content.size.toLong(), copiedBytes)
        assertArrayEquals(content, output.toByteArray())
    }

    @Test
    fun copiesEmptyAssetWithZeroByteLimit() {
        val content = byteArrayOf()
        val output = ByteArrayOutputStream()

        val copiedBytes = ByteArrayInputStream(content).copyManagedDataAsset(
            output,
            content.sha256(),
            0,
        )

        assertEquals(0, copiedBytes)
        assertArrayEquals(content, output.toByteArray())
    }

    @Test
    fun rejectsNonEmptyAssetWithZeroByteLimit() {
        val content = byteArrayOf(1)
        val output = ByteArrayOutputStream()

        assertThrows(ManagedDataAssetTooLarge::class.java) {
            ByteArrayInputStream(content).copyManagedDataAsset(
                output,
                content.sha256(),
                0,
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun rejectsAssetBeyondLimitBeforeWritingOversizedChunk() {
        val content = "oversized asset".encodeToByteArray()
        val output = ByteArrayOutputStream()

        assertThrows(ManagedDataAssetTooLarge::class.java) {
            ByteArrayInputStream(content).copyManagedDataAsset(
                output,
                content.sha256(),
                content.size.toLong() - 1L,
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun rejectsAssetWithUnexpectedHash() {
        val content = "tampered asset".encodeToByteArray()
        val output = ByteArrayOutputStream()

        val failure = assertThrows(ManagedDataAssetHashMismatch::class.java) {
            ByteArrayInputStream(content).copyManagedDataAsset(
                output,
                ByteArray(32).sha256(),
                content.size.toLong(),
            )
        }

        assertEquals(content.sha256(), failure.actual)
        assertArrayEquals(content, output.toByteArray())
    }

    @Test
    fun toleratesZeroLengthBulkRead() {
        val content = "zero-length read".encodeToByteArray()
        val output = ByteArrayOutputStream()
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(content)
            private var returnZero = true

            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                return delegate.read(buffer, offset, length)
            }
        }

        val copiedBytes = input.copyManagedDataAsset(
            output,
            content.sha256(),
            content.size.toLong(),
        )

        assertEquals(content.size.toLong(), copiedBytes)
        assertArrayEquals(content, output.toByteArray())
    }
}
