package org.fcitx.fcitx5.android.core.data

import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

internal const val MAX_PLUGIN_XML_EVENTS = 4096
internal const val MAX_PLUGIN_XML_TEXT_CHARS = 64 * 1024

internal class InvalidPluginXml(message: String) : IllegalArgumentException(message)

internal enum class PluginTrustFailure {
    InvalidPackageName,
    SignatureMismatch,
}

internal fun evaluatePluginTrust(
    packageName: String,
    isDebugBuild: Boolean,
    signatureResult: Int,
): PluginTrustFailure? {
    val prefix = PluginDescriptor.pluginPackagePrefix
    val hasDebugSuffix = packageName.endsWith(".debug")
    val basePackageName = if (hasDebugSuffix) packageName.removeSuffix(".debug") else packageName
    if (!basePackageName.startsWith(prefix) ||
        basePackageName.length == prefix.length ||
        hasDebugSuffix != isDebugBuild
    ) {
        return PluginTrustFailure.InvalidPackageName
    }
    if (signatureResult != PackageManager.SIGNATURE_MATCH) {
        return PluginTrustFailure.SignatureMismatch
    }
    return null
}

internal data class ParsedPluginXml(
    val apiVersion: String?,
    val domain: String?,
    val description: String?,
    val hasService: Boolean,
)

internal fun parsePluginXml(parser: XmlPullParser): ParsedPluginXml {
    var apiVersion: String? = null
    var domain: String? = null
    var description: String? = null
    var hasService = false
    var capturedElement: String? = null
    val capturedText = StringBuilder()
    var eventType = parser.eventType
    var eventCount = 0
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (++eventCount > MAX_PLUGIN_XML_EVENTS) {
            throw InvalidPluginXml("Plugin descriptor contains too many XML events")
        }
        when (eventType) {
            XmlPullParser.START_TAG -> {
                if (capturedElement != null) {
                    throw InvalidPluginXml("Plugin descriptor contains nested text elements")
                }
                capturedElement = parser.name.takeIf {
                    it == "apiVersion" ||
                        it == "domain" ||
                        it == "description" ||
                        it == "hasService"
                }
                capturedText.clear()
            }
            XmlPullParser.TEXT -> if (capturedElement != null) {
                val text = parser.text ?: ""
                if (text.length > MAX_PLUGIN_XML_TEXT_CHARS - capturedText.length) {
                    throw InvalidPluginXml("Plugin descriptor text is too long")
                }
                capturedText.append(text)
            }
            XmlPullParser.END_TAG -> if (parser.name == capturedElement) {
                val value = capturedText.toString()
                when (capturedElement) {
                    "apiVersion" -> apiVersion = value
                    "domain" -> domain = value
                    "description" -> description = value
                    "hasService" -> hasService = value.trim().equals("true", ignoreCase = true)
                }
                capturedElement = null
                capturedText.clear()
            }
        }
        eventType = parser.next()
    }
    return ParsedPluginXml(apiVersion, domain, description, hasService)
}

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
