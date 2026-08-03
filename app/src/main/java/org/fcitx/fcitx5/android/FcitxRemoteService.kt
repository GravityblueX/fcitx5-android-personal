/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionProvider
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.IFcitxRemoteService
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.core.reloadQuickPhrase
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.handwriting.HandwritingProviderRegistry
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.desc
import org.fcitx.fcitx5.android.utils.descEquals
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FcitxRemoteService : Service() {

    private val clipboardTransformerLock = Mutex()

    private val scope = MainScope() + CoroutineName("FcitxRemoteService")

    private val clipboardTransformers = CopyOnWriteArrayList<IClipboardEntryTransformer>()

    private val clipboardTransformerDeathRecipients = ConcurrentHashMap<IBinder, IBinder.DeathRecipient>()

    private fun transformClipboard(source: String): String {
        var result = source
        clipboardTransformers.forEach {
            try {
                result = it.transform(result)!!
            } catch (e: Exception) {
                Timber.w("Exception while calling clipboard transformer '${it.desc}'")
                Timber.w(e)
            }
        }
        return result
    }

    private fun clipboardTransformerPriority(transformer: IClipboardEntryTransformer): Int =
        runCatching { transformer.priority }.getOrElse {
            Timber.w(it, "Cannot query clipboard transformer priority: %s", transformer.desc)
            Int.MIN_VALUE
        }

    private suspend fun updateClipboardManager() = clipboardTransformerLock.withLock {
        ClipboardManager.transformer =
            if (clipboardTransformers.isEmpty()) null else ::transformClipboard
        Timber.d("All clipboard transformers: ${clipboardTransformers.joinToString { it.desc }}")
    }

    private val binder = object : IFcitxRemoteService.Stub() {
        override fun getVersionName(): String = Const.versionName

        override fun getPid(): Int = Process.myPid()

        override fun getLoadedPlugins(): MutableMap<String, String> =
            DataManager.getLoadedPlugins().map {
                it.packageName to it.versionName
            }.let { mutableMapOf<String, String>().apply { putAll(it) } }

        override fun restartFcitx() {
            FcitxDaemon.restartFcitx()
        }

        override fun registerClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            val description = runCatching { transformer.description }.getOrElse {
                Timber.w(it, "Cannot query clipboard transformer description")
                return
            }
            Timber.d("registerClipboardEntryTransformer: $description")
            if (description.isNullOrBlank()) {
                Timber.w("Cannot register ClipboardEntryTransformer of null or empty description")
                return
            }
            scope.launch {
                if (clipboardTransformers.any { it.descEquals(transformer) }) {
                    Timber.w("ClipboardEntryTransformer ${transformer.desc} has already been registered")
                    return@launch
                }
                val binder = transformer.asBinder()
                val deathRecipient = IBinder.DeathRecipient {
                    removeClipboardEntryTransformer(transformer, unlink = false)
                }
                runCatching { binder.linkToDeath(deathRecipient, 0) }.getOrElse {
                    Timber.w(it, "Clipboard transformer %s died during registration", transformer.desc)
                    return@launch
                }
                clipboardTransformerDeathRecipients[binder] = deathRecipient
                clipboardTransformers.add(transformer)
                clipboardTransformers.sortByDescending(::clipboardTransformerPriority)
                updateClipboardManager()
            }
        }

        override fun unregisterClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            Timber.d("unregisterClipboardEntryTransformer: ${transformer.desc}")
            removeClipboardEntryTransformer(transformer, unlink = true)
        }

        override fun registerHandwritingRecognitionProvider(
            provider: IHandwritingRecognitionProvider
        ) {
            HandwritingProviderRegistry.register(provider)
        }

        override fun unregisterHandwritingRecognitionProvider(
            provider: IHandwritingRecognitionProvider
        ) {
            HandwritingProviderRegistry.unregister(provider)
        }

        override fun reloadPinyinDict() {
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadPinyinDict() }
        }

        override fun reloadQuickPhrase() {
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadQuickPhrase() }
        }
    }

    private fun removeClipboardEntryTransformer(
        transformer: IClipboardEntryTransformer,
        unlink: Boolean,
    ) {
        scope.launch {
            val registered = clipboardTransformers.firstOrNull {
                it == transformer || it.descEquals(transformer)
            } ?: return@launch
            clipboardTransformers.remove(registered)
            val binder = registered.asBinder()
            clipboardTransformerDeathRecipients.remove(binder)?.let { deathRecipient ->
                if (unlink) {
                    runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                }
            }
            updateClipboardManager()
        }
    }

    override fun onCreate() {
        Timber.d("FcitxRemoteService onCreate")
        super.onCreate()
    }

    override fun onBind(intent: Intent): IBinder {
        Timber.d("FcitxRemoteService onBind: $intent")
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.d("FcitxRemoteService onUnbind: $intent")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.d("FcitxRemoteService onDestroy")
        scope.cancel()
        clipboardTransformerDeathRecipients.forEach { (binder, deathRecipient) ->
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        clipboardTransformerDeathRecipients.clear()
        clipboardTransformers.clear()
        HandwritingProviderRegistry.clear()
        runBlocking { updateClipboardManager() }
        super.onDestroy()
    }
}
