package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CropImageActivityTest {

    @Test
    fun acceptsMissingOutputAfterCreationFailure() {
        val failure = IOException("creation failed")

        cleanupFailedCrop(null, failure)

        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun removesFailedCropOutput() {
        val root = Files.createTempDirectory("failed-crop-").toFile()
        try {
            val output = root.resolve("cropped.png").also { it.writeText("partial") }
            val failure = IOException("crop failed")

            cleanupFailedCrop(output, failure)

            assertFalse(output.exists())
            assertTrue(failure.suppressed.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservesFailedCropCleanupFailure() {
        val root = Files.createTempDirectory("failed-crop-").toFile()
        try {
            val output = UndeletableFile(root.resolve("cropped.png").path)
                .also { it.writeText("partial") }
            val failure = IOException("crop failed")

            cleanupFailedCrop(output, failure)

            assertTrue(output.exists())
            assertEquals(1, failure.suppressed.size)
            assertTrue(failure.suppressed.single().message.orEmpty().contains(output.path))
        } finally {
            root.deleteRecursively()
        }
    }

    private class UndeletableFile(path: String) : File(path) {
        override fun delete(): Boolean = false
    }
}
