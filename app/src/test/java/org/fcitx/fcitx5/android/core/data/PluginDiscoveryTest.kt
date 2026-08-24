package org.fcitx.fcitx5.android.core.data

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun trustsMatchingReleasePluginIdentity() {
        assertNull(
            evaluatePluginTrust(
                "${PluginDescriptor.pluginPackagePrefix}rime",
                isDebugBuild = false,
                signatureResult = PackageManager.SIGNATURE_MATCH,
            )
        )
        assertNull(
            evaluatePluginTrust(
                "${PluginDescriptor.pluginPackagePrefix}rime.debug",
                isDebugBuild = true,
                signatureResult = PackageManager.SIGNATURE_MATCH,
            )
        )
    }

    @Test
    fun rejectsMismatchedPluginSignature() {
        assertEquals(
            PluginTrustFailure.SignatureMismatch,
            evaluatePluginTrust(
                "${PluginDescriptor.pluginPackagePrefix}rime",
                isDebugBuild = false,
                signatureResult = PackageManager.SIGNATURE_NO_MATCH,
            ),
        )
    }

    @Test
    fun rejectsWrongPackagePrefixOrBuildVariant() {
        assertEquals(
            PluginTrustFailure.InvalidPackageName,
            evaluatePluginTrust(
                "example.plugin.rime",
                isDebugBuild = false,
                signatureResult = PackageManager.SIGNATURE_MATCH,
            ),
        )
        assertEquals(
            PluginTrustFailure.InvalidPackageName,
            evaluatePluginTrust(
                PluginDescriptor.pluginPackagePrefix,
                isDebugBuild = false,
                signatureResult = PackageManager.SIGNATURE_MATCH,
            ),
        )
        assertEquals(
            PluginTrustFailure.InvalidPackageName,
            evaluatePluginTrust(
                "${PluginDescriptor.pluginPackagePrefix}rime.debug",
                isDebugBuild = false,
                signatureResult = PackageManager.SIGNATURE_MATCH,
            ),
        )
        assertEquals(
            PluginTrustFailure.InvalidPackageName,
            evaluatePluginTrust(
                "${PluginDescriptor.pluginPackagePrefix}rime",
                isDebugBuild = true,
                signatureResult = PackageManager.SIGNATURE_MATCH,
            ),
        )
    }
}
