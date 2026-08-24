package org.fcitx.fcitx5.android.core.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val MAX_MANAGED_DATA_FILE_BYTES = 128L * 1024L * 1024L
internal const val MAX_PLUGIN_DATA_BYTES = 128L * 1024L * 1024L
internal const val MAX_MANAGED_DATA_SYNC_BYTES = 256L * 1024L * 1024L

internal sealed class InvalidDataAsset(message: String) : IOException(message)

internal class ManagedDataAssetTooLarge(val maxBytes: Long) :
    InvalidDataAsset("Managed data asset exceeds limit of $maxBytes bytes")

internal class ManagedDataAssetHashMismatch(
    val expected: String,
    val actual: String,
) : InvalidDataAsset("Managed data asset hash mismatch: expected $expected, got $actual")

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

internal fun InputStream.copyManagedDataAsset(
    output: OutputStream,
    expectedSHA256: String,
    maxBytes: Long,
): Long {
    require(maxBytes >= 0)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copiedBytes = 0L
    while (true) {
        val remainingBytes = maxBytes - copiedBytes
        val readLimit = if (remainingBytes >= buffer.size) {
            buffer.size
        } else {
            (remainingBytes + 1L).toInt()
        }
        val count = read(buffer, 0, readLimit)
        when {
            count < 0 -> break
            count > remainingBytes -> throw ManagedDataAssetTooLarge(maxBytes)
            count > 0 -> {
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                copiedBytes += count
            }
            else -> {
                val next = read()
                if (next < 0) break
                if (copiedBytes >= maxBytes) throw ManagedDataAssetTooLarge(maxBytes)
                digest.update(next.toByte())
                output.write(next)
                copiedBytes++
            }
        }
    }
    val actualSHA256 = digest.digest().toLowercaseHex()
    if (!actualSHA256.equals(expectedSHA256, ignoreCase = true)) {
        throw ManagedDataAssetHashMismatch(expectedSHA256, actualSHA256)
    }
    return copiedBytes
}
