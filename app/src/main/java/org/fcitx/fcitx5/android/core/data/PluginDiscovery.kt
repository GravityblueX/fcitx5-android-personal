package org.fcitx.fcitx5.android.core.data

internal sealed interface PluginDiscoveryResult {
    data class Loaded(val descriptor: PluginDescriptor) : PluginDiscoveryResult

    data class Failed(val reason: PluginLoadFailed) : PluginDiscoveryResult
}

internal fun discoverPluginPackages(
    packageNames: Iterable<String>,
    onException: (String, Exception) -> Unit = { _, _ -> },
    loadPlugin: (String) -> PluginDiscoveryResult,
): DataManager.PluginSet {
    val loaded = linkedSetOf<PluginDescriptor>()
    val failed = linkedMapOf<String, PluginLoadFailed>()
    packageNames.distinct().forEach { packageName ->
        val result = try {
            loadPlugin(packageName)
        } catch (failure: Exception) {
            onException(packageName, failure)
            PluginDiscoveryResult.Failed(PluginLoadFailed.PluginDescriptorParseError)
        }
        when (result) {
            is PluginDiscoveryResult.Loaded -> loaded.add(result.descriptor)
            is PluginDiscoveryResult.Failed -> failed[packageName] = result.reason
        }
    }
    return DataManager.PluginSet(loaded, failed)
}
