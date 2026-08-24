package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PluginDescriptorIdentityTest {

    private fun descriptor(packageName: String) = PluginDescriptor(
        packageName = packageName,
        apiVersion = PluginDescriptor.pluginAPI,
        domain = null,
        description = "plugin",
        hasService = true,
        versionName = "1",
        nativeLibraryDir = "",
    )

    @Test
    fun keepsRuntimeIdentityUniqueWhenDerivedNamesCollide() {
        val official = descriptor("${PluginDescriptor.pluginPackagePrefix}rime")
        val colliding = descriptor("rime${PluginDescriptor.pluginPackageSuffix}")

        assertEquals(official.name, colliding.name)
        assertNotEquals(official.runtimeId, colliding.runtimeId)
        assertEquals(official.packageName, official.runtimeId)
        assertEquals(colliding.packageName, colliding.runtimeId)
    }
}
