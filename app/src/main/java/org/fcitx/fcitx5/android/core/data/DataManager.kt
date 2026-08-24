/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.AssetManager
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.data.DataManager.dataDir
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.cleanupStagedFileInstalls
import org.fcitx.fcitx5.android.utils.ensureDirectory
import org.fcitx.fcitx5.android.utils.isJavaIdentifier
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.replaceFileAtomically
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Build up a Filesystem hierarchy at [dataDir]
 *
 * Operations are synchronized
 */
object DataManager {

    data class PluginSet(
        val loaded: Set<PluginDescriptor>,
        val failed: Map<String, PluginLoadFailed>
    )

    const val PLUGIN_INTENT = "${BuildConfig.APPLICATION_ID}.plugin.MANIFEST"
    private const val OFFICIAL_PLUGIN_INTENT =
        "org.fcitx.fcitx5.android.plugin.MANIFEST"
    private const val OFFICIAL_DEBUG_PLUGIN_INTENT =
        "org.fcitx.fcitx5.android.debug.plugin.MANIFEST"

    private val compatiblePluginIntents = linkedSetOf(
        PLUGIN_INTENT,
        OFFICIAL_PLUGIN_INTENT,
        OFFICIAL_DEBUG_PLUGIN_INTENT
    )

    private val lock = ReentrantLock()

    private val json by lazy { Json { prettyPrint = true } }

    @Volatile
    var synced = false
        private set

    // should be consistent with the deserialization in DataDescriptorPlugin (:build-logic)
    private fun deserializeDataDescriptor(raw: String): DataDescriptor {
        return json.decodeFromString<DataDescriptor>(raw)
    }

    private fun deserializeDataDescriptor(
        input: InputStream,
        maxBytes: Int = MAX_DATA_DESCRIPTOR_BYTES,
    ): DataDescriptor {
        return deserializeDataDescriptor(input.readBoundedDataDescriptorText(maxBytes))
    }

    private fun serializeDataDescriptor(descriptor: DataDescriptor): String {
        return json.encodeToString(descriptor)
    }

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    val dataDir: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Timber.d("Using device protected storage")
        appContext.createDeviceProtectedStorageContext().dataDir
    } else {
        File(appContext.applicationInfo.dataDir)
    }

    private fun AssetManager.getDataDescriptor(): DataDescriptor {
        return open(BuildConfig.DATA_DESCRIPTOR_NAME)
            .use { deserializeDataDescriptor(it) }
    }

    private fun AssetManager.validateDataDescriptorAssets(descriptor: DataDescriptor) {
        var totalBytes = 0L
        descriptor.files.forEach { (path, sha256) ->
            if (sha256.isBlank()) return@forEach
            open(path).use { input ->
                val availableBytes = input.available().toLong()
                if (availableBytes > MAX_MANAGED_DATA_FILE_BYTES) {
                    throw ManagedDataAssetTooLarge(MAX_MANAGED_DATA_FILE_BYTES)
                }
                totalBytes += availableBytes
                if (totalBytes > MAX_PLUGIN_DATA_BYTES) {
                    throw ManagedDataAssetTooLarge(MAX_PLUGIN_DATA_BYTES)
                }
            }
        }
    }

    private val loadedPlugins = mutableSetOf<PluginDescriptor>()
    private val failedPlugins = mutableMapOf<String, PluginLoadFailed>()

    fun getLoadedPlugins(): Set<PluginDescriptor> = lock.withLock { loadedPlugins.toSet() }
    fun getFailedPlugins(): Map<String, PluginLoadFailed> = lock.withLock { failedPlugins.toMap() }

    fun getSyncedPluginSet() = lock.withLock {
        PluginSet(loadedPlugins.toSet(), failedPlugins.toMap())
    }

    /**
     * Will be cleared after each sync
     */
    private val callbacks = mutableListOf<() -> Unit>()

    fun addOnNextSyncedCallback(block: () -> Unit) = lock.withLock {
        callbacks.add(block)
    }

    fun removeOnNextSyncedCallback(block: () -> Unit) = lock.withLock {
        callbacks.remove(block)
    }

    fun whenSynced(block: () -> Unit) {
        val runImmediately = lock.withLock {
            if (synced) true else {
                callbacks.add(block)
                false
            }
        }
        if (runImmediately) block()
    }

    private fun queryPluginActivities(pm: PackageManager): List<ResolveInfo> =
        compatiblePluginIntents.flatMap { action ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        Intent(action),
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                } else {
                    pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                }
            } catch (failure: Exception) {
                Timber.w(failure, "Failed to query plugin activities for '$action'")
                emptyList()
            }
        }.mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            packageName to resolveInfo
        }.distinctBy { it.first }.map { it.second }

    fun findPluginActivity(packageName: String): ComponentName? =
        queryPluginActivities(appContext.packageManager)
            .firstOrNull { it.activityInfo.packageName == packageName }
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }

    fun findPluginActivationActivity(packageName: String): ComponentName? {
        val intent = Intent("${BuildConfig.APPLICATION_ID}.plugin.ACTIVATE")
            .setPackage(packageName)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_ALL)
        }
        return info?.activityInfo?.let {
            ComponentName(it.packageName, it.name)
        }
    }

    private fun loadPluginDescriptor(
        pm: PackageManager,
        packageName: String,
    ): PluginDiscoveryResult {
        val res = pm.getResourcesForApplication(packageName)

        @SuppressLint("DiscouragedApi")
        val resId = res.getIdentifier("plugin", "xml", packageName)
        if (resId == 0) {
            Timber.w("Failed to get the plugin descriptor of $packageName")
            return PluginDiscoveryResult.Failed(PluginLoadFailed.MissingPluginDescriptor)
        }
        val parser = res.getXml(resId)
        var domain: String? = null
        var apiVersion: String? = null
        var description: String? = null
        var hasService = false
        var text: String? = null
        try {
            var eventType = parser.eventType
            while ((eventType != XmlPullParser.END_DOCUMENT)) {
                when (eventType) {
                    XmlPullParser.TEXT -> text = parser.text
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "apiVersion" -> apiVersion = text
                        "domain" -> domain = text
                        "description" -> description = text
                        "hasService" -> hasService = text?.lowercase() == "true"
                    }
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }

        if (description?.startsWith("@string/") == true) {
            // Replace "@string/" with string resource
            val s = description.substring(8)
            if (s.isJavaIdentifier()) {
                @SuppressLint("DiscouragedApi")
                val id = res.getIdentifier(s, "string", packageName)
                if (id != 0) description = res.getString(id)
            }
        }

        if (apiVersion == null || description == null) {
            Timber.w("Failed to parse plugin descriptor of $packageName")
            return PluginDiscoveryResult.Failed(PluginLoadFailed.PluginDescriptorParseError)
        }
        if (PluginDescriptor.pluginAPI != apiVersion) {
            Timber.w("$packageName's api version [$apiVersion] doesn't match with the current [${PluginDescriptor.pluginAPI}]")
            return PluginDiscoveryResult.Failed(
                PluginLoadFailed.PluginAPIIncompatible(apiVersion)
            )
        }
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        }
        return PluginDiscoveryResult.Loaded(
            PluginDescriptor(
                packageName,
                apiVersion,
                domain,
                description,
                hasService,
                info.versionName ?: "",
                info.applicationInfo?.nativeLibraryDir ?: ""
            )
        )
    }

    fun detectPlugins(): PluginSet {
        val pm = appContext.packageManager
        val pluginPackages = queryPluginActivities(pm).map {
            it.activityInfo.packageName
        }

        Timber.d("Detected plugin packages: ${pluginPackages.joinToString()}")

        return discoverPluginPackages(
            pluginPackages,
            loadPlugin = { packageName -> loadPluginDescriptor(pm, packageName) },
            onException = { packageName, failure ->
                Timber.w(failure, "Failed to inspect plugin package '$packageName'")
            },
        )
    }

    fun sync() = lock.withLock {
        synced = false
        loadedPlugins.clear()
        failedPlugins.clear()

        dataDir.ensureDirectory()
        cleanupStagedDataWrites(dataDir)
        val destDescriptorFile = File(dataDir, BuildConfig.DATA_DESCRIPTOR_NAME)

        // load last run's data descriptor
        val oldDescriptor = destDescriptorFile
            .runCatching {
                inputStream().use {
                    deserializeDataDescriptor(it, MAX_STORED_DATA_DESCRIPTOR_BYTES)
                }
                    .withValidatedManagedPaths()
            }
            .onFailure { failure ->
                if (destDescriptorFile.exists()) {
                    Timber.w(failure, "Ignoring invalid stored data descriptor")
                }
            }
            .getOrElse { DataDescriptor("", emptyMap(), emptyMap()) }

        // load app's data descriptor
        val mainDescriptor = appContext.assets.getDataDescriptor()

        val (parsedDescriptors, failed) = detectPlugins()
        failedPlugins.putAll(failed)

        Timber.d("Plugins to load: $parsedDescriptors")

        // Create an empty hierarchy
        val newHierarchy = DataHierarchy()
        // Always add app's first
        newHierarchy.install(mainDescriptor, FileSource.Main)

        val pluginAssets = mutableMapOf<String, AssetManager>()

        // Add plugin's one by one
        for (plugin in parsedDescriptors) {
            val pluginContext = appContext.createPackageContext(plugin.packageName, 0)
            val assets = pluginContext.assets
            val descriptor = try {
                assets.getDataDescriptor()
                    .withValidatedManagedPaths()
                    .also { assets.validateDataDescriptorAssets(it) }
            } catch (failure: Exception) {
                Timber.w(failure, "Failed to validate data assets of '${plugin.name}'")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.DataDescriptorParseError(plugin)
                continue
            }
            try {
                newHierarchy.install(descriptor, FileSource.Plugin(plugin))
            } catch (e: DataHierarchy.PathConflict) {
                Timber.w("Path '${e.path}' has already been created by '${e.src}', cannot create file")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.PathConflict(plugin, e.path, e.src)
                continue
            } catch (e: DataHierarchy.SymlinkConflict) {
                Timber.w("Path '${e.path}' has already been created by '${e.src}', cannot create symlink")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.PathConflict(plugin, e.path, e.src)
                continue
            } catch (e: InvalidDataDescriptor) {
                Timber.w(e, "Plugin '${plugin.name}' contains an invalid data descriptor")
                failedPlugins[plugin.packageName] =
                    PluginLoadFailed.DataDescriptorParseError(plugin)
                continue
            }
            pluginAssets[plugin.runtimeId] = assets
            loadedPlugins.add(plugin)
            Timber.d("Merged data hierarchy of ${plugin.name}")
        }

        Timber.d("Hierarchy created")

        val newDescriptor = newHierarchy.downToDataDescriptor()
        var remainingSyncBytes = MAX_MANAGED_DATA_SYNC_BYTES
        val remainingPluginBytes = pluginAssets.keys
            .associateWith { MAX_PLUGIN_DATA_BYTES }
            .toMutableMap()

        fun copyLimit(source: FileSource): Long {
            val remainingSourceBytes = if (source is FileSource.Plugin) {
                remainingPluginBytes.getValue(source.descriptor.runtimeId)
            } else {
                MAX_MANAGED_DATA_SYNC_BYTES
            }
            return minOf(
                MAX_MANAGED_DATA_FILE_BYTES,
                remainingSyncBytes,
                remainingSourceBytes,
            )
        }

        fun consumeCopyBudget(source: FileSource, copiedBytes: Long) {
            remainingSyncBytes -= copiedBytes
            if (source is FileSource.Plugin) {
                remainingPluginBytes[source.descriptor.runtimeId] =
                    remainingPluginBytes.getValue(source.descriptor.runtimeId) - copiedBytes
            }
        }

        // Compute the difference of the created one and the old one
        // Run actions to migrate to the new hierarchy
        DataHierarchy.diff(oldDescriptor, newHierarchy).sortedByDescending { it.ordinal }.forEach {
            Timber.d("Action: $it")
            when (it) {
                is FileAction.CreateFile -> {
                    val assets = if (it.src is FileSource.Plugin)
                        pluginAssets.getValue(it.src.descriptor.runtimeId)
                    else appContext.assets
                    val copiedBytes = assets.copyFile(
                        it.path,
                        newDescriptor.files.getValue(it.path),
                        copyLimit(it.src),
                    )
                    consumeCopyBudget(it.src, copiedBytes)
                }
                is FileAction.DeleteDir -> {
                    removePath(it.path).getOrThrow()
                }
                is FileAction.DeleteFile -> {
                    removePath(it.path).getOrThrow()
                }
                is FileAction.UpdateFile -> {
                    val assets = if (it.src is FileSource.Plugin)
                        pluginAssets.getValue(it.src.descriptor.runtimeId)
                    else appContext.assets
                    val copiedBytes = assets.copyFile(
                        it.path,
                        newDescriptor.files.getValue(it.path),
                        copyLimit(it.src),
                    )
                    consumeCopyBudget(it.src, copiedBytes)
                }
                is FileAction.CreateSymlink -> {
                    removePath(it.path).getOrThrow()
                    symlink(it.src, it.path).getOrThrow()
                }
            }
        }
        // save the new hierarchy as the data descriptor to be used in the next run
        replaceFileAtomically(destDescriptorFile) { staged ->
            staged.bufferedWriter().use {
                it.write(serializeDataDescriptor(newDescriptor))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // remove old assets from credential encrypted storage
            val oldDataDir = appContext.dataDir
            val oldDataDescriptor = oldDataDir.resolve(BuildConfig.DATA_DESCRIPTOR_NAME)
            if (oldDataDescriptor.exists()) {
                listOf(
                    oldDataDescriptor,
                    oldDataDir.resolve("README.md"),
                    oldDataDir.resolve("usr"),
                ).forEach { obsolete ->
                    FileUtil.removeFile(obsolete).onFailure { failure ->
                        Timber.w(failure, "Failed to remove old data path: ${obsolete.path}")
                    }
                }
            }
        }
        synced = true
        callbacks.toList().also { callbacks.clear() }.forEach { it() }
        Timber.d("Synced")
    }

    private fun removePath(path: String) =
        FileUtil.removeFile(resolveManagedDataPath(dataDir, path))

    private fun symlink(source: String, target: String) =
        FileUtil.symlink(
            resolveManagedDataSource(dataDir, source),
            resolveManagedDataPath(dataDir, target),
        )

    private fun AssetManager.copyFile(
        filename: String,
        expectedSHA256: String,
        maxBytes: Long,
    ): Long {
        val destination = resolveManagedDataPath(dataDir, filename)
        val parent = destination.parentFile ?: error("Cannot resolve parent for '${filename}'")
        parent.ensureDirectory()
        cleanupStagedDataWrites(parent)
        var copiedBytes = 0L
        replaceFileAtomically(destination) { staged ->
            open(filename).use { input ->
                staged.outputStream().use { output ->
                    copiedBytes = input.copyManagedDataAsset(output, expectedSHA256, maxBytes)
                }
            }
        }
        return copiedBytes
    }

    fun deleteAndSync() {
        lock.withLock {
            listOf(
                dataDir.resolve(BuildConfig.DATA_DESCRIPTOR_NAME),
                dataDir.resolve("README.md"),
                dataDir.resolve("usr"),
            ).forEach { FileUtil.removeFile(it).getOrThrow() }
        }
        sync()
    }

}

internal fun isLegacyDataWriteStagingFile(fileName: String): Boolean =
    (fileName.startsWith("data-descriptor-") || fileName.startsWith("data-file-")) &&
            fileName.endsWith(".staged")

internal fun cleanupStagedDataWrites(directory: File) {
    directory.listFiles()
        ?.filter { file -> file.isFile && isLegacyDataWriteStagingFile(file.name) }
        ?.forEach { staged ->
            staged.removeIfExists().onFailure {
                Timber.w(it, "Failed to remove stale data write: ${staged.path}")
            }
        }
    cleanupStagedFileInstalls(directory)
}
