/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.os.IBinder
import org.fcitx.fcitx5.android.common.handwriting.HandwritingProtocol
import org.fcitx.fcitx5.android.common.handwriting.IHandwritingRecognitionProvider
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

object HandwritingProviderRegistry {

    data class Provider(
        val id: String,
        val supportedModes: Set<Int>,
        val remote: IHandwritingRecognitionProvider,
    )

    private data class Entry(
        val provider: Provider,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val lock = Any()
    private val entries = linkedMapOf<IBinder, Entry>()
    private val onChangeListeners = CopyOnWriteArrayList<() -> Unit>()
    private var builtInProvider: Provider? = null
    private var reloadBuiltInProvider: (((Boolean) -> Unit) -> Unit)? = null

    fun addOnChangeListener(listener: () -> Unit) {
        onChangeListeners.addIfAbsent(listener)
    }

    fun removeOnChangeListener(listener: () -> Unit) {
        onChangeListeners.remove(listener)
    }

    private fun notifyChanged() {
        onChangeListeners.forEach { it() }
    }

    fun installBuiltIn(provider: BuiltInHandwritingRecognitionProvider) {
        val remote = provider.remote
        val version = remote.protocolVersion
        require(version == HandwritingProtocol.VERSION) {
            "Built-in handwriting protocol version $version does not match " +
                    HandwritingProtocol.VERSION
        }
        val descriptor = Provider(
            id = remote.providerId,
            supportedModes = remote.supportedModes.toSet(),
            remote = remote,
        )
        synchronized(lock) {
            builtInProvider = descriptor
            reloadBuiltInProvider = provider::reload
        }
        Timber.i(
            "Installed built-in handwriting provider %s for modes %s",
            descriptor.id,
            descriptor.supportedModes,
        )
        notifyChanged()
    }

    fun reloadBuiltIn(onComplete: (Boolean) -> Unit = {}): Boolean {
        val reload = synchronized(lock) { reloadBuiltInProvider } ?: run {
            onComplete(false)
            return false
        }
        reload { success ->
            if (success) notifyChanged()
            onComplete(success)
        }
        return true
    }

    fun register(remote: IHandwritingRecognitionProvider) {
        val binder = remote.asBinder()
        val version = runCatching { remote.protocolVersion }.getOrElse {
            Timber.w(it, "Cannot query handwriting provider protocol version")
            return
        }
        if (version != HandwritingProtocol.VERSION) {
            Timber.w(
                "Ignore handwriting provider with protocol version %d; expected %d",
                version,
                HandwritingProtocol.VERSION,
            )
            return
        }
        val id = runCatching { remote.providerId }.getOrElse {
            Timber.w(it, "Cannot query handwriting provider id")
            return
        }
        if (id.isBlank()) {
            Timber.w("Ignore handwriting provider with empty id")
            return
        }
        val supportedModes = runCatching { remote.supportedModes.toSet() }.getOrElse {
            Timber.w(it, "Cannot query supported modes from handwriting provider %s", id)
            return
        }
        val deathRecipient = IBinder.DeathRecipient { removeBinder(binder, unlink = false) }
        synchronized(lock) {
            entries.remove(binder)?.let {
                binder.unlinkToDeath(it.deathRecipient, 0)
            }
            entries.entries
                .filter { it.value.provider.id == id }
                .forEach { (existingBinder, existingEntry) ->
                    entries.remove(existingBinder)
                    runCatching {
                        existingBinder.unlinkToDeath(existingEntry.deathRecipient, 0)
                    }
                }
            runCatching { binder.linkToDeath(deathRecipient, 0) }.getOrElse {
                Timber.w(it, "Handwriting provider %s died during registration", id)
                return
            }
            entries[binder] = Entry(Provider(id, supportedModes, remote), deathRecipient)
        }
        Timber.i("Registered handwriting provider %s for modes %s", id, supportedModes)
        notifyChanged()
    }

    fun unregister(remote: IHandwritingRecognitionProvider) {
        removeBinder(remote.asBinder(), unlink = true)
    }

    fun select(mode: Int): Provider? = synchronized(lock) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (binder, entry) = iterator.next()
            if (!binder.isBinderAlive) {
                iterator.remove()
                Timber.i("Removed dead handwriting provider %s", entry.provider.id)
            }
        }
        builtInProvider
            ?.takeIf {
                mode in it.supportedModes ||
                        HandwritingProtocol.MODE_AUTO in it.supportedModes
            }
            ?: entries.values
            .asSequence()
            .map { it.provider }
            .firstOrNull {
                mode in it.supportedModes || HandwritingProtocol.MODE_AUTO in it.supportedModes
            }
    }

    private fun removeBinder(binder: IBinder, unlink: Boolean) {
        val removed = synchronized(lock) {
            entries.remove(binder)
        } ?: return
        if (unlink) {
            runCatching { binder.unlinkToDeath(removed.deathRecipient, 0) }
        }
        Timber.i("Unregistered handwriting provider %s", removed.provider.id)
        notifyChanged()
    }

    fun clear() {
        val removed = synchronized(lock) {
            entries.values.toList().also { entries.clear() }
        }
        removed.forEach {
            runCatching { it.provider.remote.asBinder().unlinkToDeath(it.deathRecipient, 0) }
        }
        if (removed.isNotEmpty()) {
            notifyChanged()
        }
    }
}
