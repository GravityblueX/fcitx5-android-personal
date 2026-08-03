/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    @Volatile
    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()
    private val onUpdateListenersLock = Any()

    @Volatile
    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        synchronized(onUpdateListenersLock) { onUpdateListeners.add(listener) }
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        synchronized(onUpdateListenersLock) { onUpdateListeners.remove(listener) }
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    private val autoClearPref = AppPrefs.getInstance().clipboard.clipboardAutoClear
    private val autoClearTimeoutPref =
        AppPrefs.getInstance().clipboard.clipboardAutoClearTimeout

    private var expirationJob: Job? = null

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch {
            mutex.withLock {
                removeOutdated()
                updateItemCount()
                scheduleExpirationLocked()
            }
        }
    }

    @Keep
    private val autoClearListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        launch {
            mutex.withLock {
                if (enabled) removeExpiredLocked()
                scheduleExpirationLocked()
            }
        }
    }

    @Keep
    private val autoClearTimeoutListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            launch {
                mutex.withLock {
                    removeExpiredLocked()
                    scheduleExpirationLocked()
                }
            }
        }

    @Volatile
    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        synchronized(onUpdateListenersLock) { onUpdateListeners.toList() }
            .forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        clbDao = clbDb.clipboardDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        autoClearPref.registerOnChangeListener(autoClearListener)
        autoClearTimeoutPref.registerOnChangeListener(autoClearTimeoutListener)
        launch {
            mutex.withLock {
                removeExpiredLocked()
                updateItemCount()
                scheduleExpirationLocked()
            }
        }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allEntries() = clbDao.allEntries()

    suspend fun pin(id: Int) = mutex.withLock {
        clbDao.updatePinStatus(id, true)
        lastEntry?.let {
            if (it.id == id) lastEntry = it.copy(pinned = true)
        }
        scheduleExpirationLocked()
    }

    suspend fun unpin(id: Int) = mutex.withLock {
        clbDao.updatePinStatus(id, false)
        lastEntry?.let {
            if (it.id == id) lastEntry = it.copy(pinned = false)
        }
        removeExpiredLocked()
        scheduleExpirationLocked()
    }

    suspend fun markUsed(id: Int) = mutex.withLock {
        val timestamp = System.currentTimeMillis()
        clbDao.updateTime(id, timestamp)
        lastEntry?.let {
            if (it.id == id) lastEntry = it.copy(timestamp = timestamp)
        }
        scheduleExpirationLocked()
    }

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        mutex.withLock {
            clbDao.markAsDeleted(id)
            updateItemCount()
            scheduleExpirationLocked()
        }
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray {
        return mutex.withLock {
            val ids = if (skipPinned) {
                clbDao.findUnpinnedIds()
            } else {
                clbDao.findAllIds()
            }
            clbDao.markAsDeleted(*ids)
            updateItemCount()
            scheduleExpirationLocked()
            ids
        }
    }

    suspend fun undoDelete(vararg ids: Int) {
        mutex.withLock {
            clbDao.undoDelete(*ids)
            removeExpiredLocked()
            updateItemCount()
            scheduleExpirationLocked()
        }
    }

    suspend fun realDelete() {
        clbDao.realDelete()
    }

    suspend fun nukeTable() {
        mutex.withLock {
            withContext(coroutineContext) {
                clbDb.clearAllTables()
                updateItemCount()
                scheduleExpirationLocked()
            }
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val entries = ClipboardEntry
                    .fromClipDataItems(clip, transformer)
                    .filterNot { it.text.isBlank() }
                if (entries.isEmpty()) return@withLock
                val primaryEntry = entries.first()
                try {
                    val updatedPrimaryEntry = clbDb.withTransaction {
                        entries.asReversed().map { entry ->
                            clbDao.find(entry.text, entry.sensitive)?.let {
                                clbDao.updateTime(it.id, entry.timestamp)
                                it.copy(timestamp = entry.timestamp)
                            } ?: run {
                                val rowId = clbDao.insert(entry)
                                clbDao.get(rowId) ?: entry
                            }
                        }.also {
                            removeOutdated()
                            removeExpiredLocked()
                        }.last()
                    }
                    updateLastEntry(updatedPrimaryEntry)
                    updateItemCount()
                    scheduleExpirationLocked()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastEntry(primaryEntry)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        val unpinned = clbDao.getAllUnpinned()
        ClipboardHistoryPruner.entryIdsToDelete(unpinned, limit)
            .takeIf { it.isNotEmpty() }
            ?.let { clbDao.markAsDeleted(*it) }
    }

    private fun expirationTimeoutMillis(): Long =
        TimeUnit.HOURS.toMillis(autoClearTimeoutPref.getValue().toLong())

    private suspend fun removeExpiredLocked() {
        if (!autoClearPref.getValue()) return
        val cutoff = System.currentTimeMillis() - expirationTimeoutMillis()
        val deleted = clbDao.deleteExpiredUnpinned(cutoff)
        if (deleted > 0) {
            lastEntry?.let {
                if (!it.pinned && it.timestamp <= cutoff) lastEntry = null
            }
            updateItemCount()
        }
    }

    private suspend fun scheduleExpirationLocked() {
        expirationJob?.cancel()
        expirationJob = null
        if (!autoClearPref.getValue()) return
        val oldestTimestamp = clbDao.oldestUnpinnedTimestamp() ?: return
        val delayMillis =
            (oldestTimestamp + expirationTimeoutMillis() - System.currentTimeMillis())
                .coerceAtLeast(0L)
        expirationJob = launch {
            delay(delayMillis)
            mutex.withLock {
                expirationJob = null
                removeExpiredLocked()
                scheduleExpirationLocked()
            }
        }
    }

}
