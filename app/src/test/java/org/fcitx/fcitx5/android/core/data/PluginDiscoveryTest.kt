package org.fcitx.fcitx5.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PluginDiscoveryTest {

    private fun descriptor(packageName: String) = PluginDescriptor(
        packageName = packageName,
        apiVersion = PluginDescriptor.pluginAPI,
        domain = null,
        description = "plugin",
        hasService = false,
        versionName = "1",
        nativeLibraryDir = "",
    )

    @Test
    fun isolatesPackageFailureAndContinuesDiscovery() {
        val brokenPackage = "plugin.broken"
        val healthyPackage = "plugin.healthy"
        val failure = IllegalStateException("package disappeared")
        val observedExceptions = mutableListOf<Pair<String, Exception>>()

        val result = discoverPluginPackages(
            listOf(brokenPackage, healthyPackage),
            loadPlugin = { packageName ->
                if (packageName == brokenPackage) throw failure
                PluginDiscoveryResult.Loaded(descriptor(packageName))
            },
            onException = { packageName, exception ->
                observedExceptions.add(packageName to exception)
            },
        )

        assertEquals(setOf(descriptor(healthyPackage)), result.loaded)
        assertEquals(
            mapOf(brokenPackage to PluginLoadFailed.PluginDescriptorParseError),
            result.failed,
        )
        assertEquals(brokenPackage, observedExceptions.single().first)
        assertSame(failure, observedExceptions.single().second)
    }

    @Test
    fun preservesExpectedFailureReason() {
        val missingPackage = "plugin.missing"

        val result = discoverPluginPackages(listOf(missingPackage)) {
            PluginDiscoveryResult.Failed(PluginLoadFailed.MissingPluginDescriptor)
        }

        assertEquals(emptySet<PluginDescriptor>(), result.loaded)
        assertEquals(
            mapOf(missingPackage to PluginLoadFailed.MissingPluginDescriptor),
            result.failed,
        )
    }

    @Test
    fun inspectsDuplicatePackageOnlyOnce() {
        val packageName = "plugin.duplicate"
        var inspections = 0

        val result = discoverPluginPackages(listOf(packageName, packageName)) {
            inspections++
            PluginDiscoveryResult.Loaded(descriptor(it))
        }

        assertEquals(1, inspections)
        assertEquals(setOf(descriptor(packageName)), result.loaded)
        assertEquals(emptyMap<String, PluginLoadFailed>(), result.failed)
    }
}
