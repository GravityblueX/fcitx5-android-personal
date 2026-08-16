/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.removeIfExists
import timber.log.Timber
import java.io.File

class RecentlyUsed(val type: String, val limit: Int) {

    companion object {
        // for backwords compatibility only
        const val DIR_NAME = "recently_used"
        const val PREFERENCE_NAME = "picker_recently_used"
        private const val MIGRATION_KEY_PREFIX = "migrated_"
    }

    private val sharedPreferences = FcitxApplication.getInstance().directBootAwareContext
        .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    private val initialItems = migrate() ?: load()

    private val map = LinkedHashMap<String, Boolean>(limit).apply {
        normalizeRecentlyUsed(initialItems, limit).forEach { item -> put(item, true) }
    }

    init {
        if (map.keys.toList() != initialItems) save()
    }

    private val migrationKey get() = "$MIGRATION_KEY_PREFIX$type"

    val items: List<String> get() = map.keys.reversed()

    private fun load(): List<String> {
        val rawValue = sharedPreferences.getString(type, "") ?: ""
        if (rawValue.isEmpty()) {
            return emptyList()
        }
        return try {
            Json.decodeFromString<List<String>>(rawValue)
        } catch (_: Exception) {
            sharedPreferences.edit {
                remove(type)
            }
            emptyList()
        }
    }

    private fun save() {
        sharedPreferences.edit {
            putString(type, Json.encodeToString<List<String>>(map.keys.toList()))
        }
    }

    fun insert(item: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            map.putLast(item, true)
        } else {
            // LinkedHashMap's encounter order is not affected when `put` an existing key
            if (map.containsKey(item)) {
                map.remove(item)
            }
            map.put(item, true)
        }
        if (map.size > limit) {
            map.remove(map.entries.first().key)
        }
        save()
    }

    fun migrate(): List<String>? {
        val dir = FcitxApplication.getInstance().directBootAwareContext.filesDir.resolve(DIR_NAME)
        val file = dir.resolve(type)
        if (sharedPreferences.getBoolean(migrationKey, false)) {
            cleanupMigratedRecentlyUsed(file, dir).forEach { result ->
                result.onFailure { failure ->
                    Timber.w(failure, "Failed to clean migrated RecentlyUsed(type=$type)")
                }
            }
            return null
        }
        if (file.exists()) {
            try {
                val lines = file.readLines()
                check(
                    sharedPreferences.edit()
                        .putString(type, Json.encodeToString<List<String>>(lines))
                        .putBoolean(migrationKey, true)
                        .commit()
                ) { "Failed to save RecentlyUsed(type=$type)" }
                cleanupMigratedRecentlyUsed(file, dir).forEach { result ->
                    result.onFailure { failure ->
                        Timber.w(failure, "Failed to clean migrated RecentlyUsed(type=$type)")
                    }
                }
                return lines
            } catch (e: Exception) {
                Timber.w("Failed to migrate RecentlyUsed(type=$type)")
                Timber.w(e)
                return null
            }
        }
        return null
    }
}

internal fun cleanupMigratedRecentlyUsed(file: File, directory: File): List<Result<Unit>> =
    buildList {
        val fileCleanup = file.removeIfExists()
        add(fileCleanup)
        if (fileCleanup.isSuccess && directory.list()?.isEmpty() == true) {
            add(directory.removeIfExists())
        }
    }

internal fun normalizeRecentlyUsed(items: List<String>, limit: Int): List<String> {
    require(limit > 0) { "Recently used item limit must be positive" }
    val recentItems = LinkedHashMap<String, Boolean>(limit)
    items.forEach { item ->
        if (item.isBlank()) return@forEach
        recentItems.remove(item)
        recentItems[item] = true
        if (recentItems.size > limit) {
            recentItems.remove(recentItems.entries.first().key)
        }
    }
    return recentItems.keys.toList()
}
