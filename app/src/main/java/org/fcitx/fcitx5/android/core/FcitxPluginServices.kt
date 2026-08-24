/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.PluginDescriptor
import org.fcitx.fcitx5.android.core.data.evaluatePluginTrust
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

object FcitxPluginServices {

    const val PLUGIN_SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SERVICE"
    private val builtInReplacementPackages = setOf(
        "org.fcitx.fcitx5.android.plugin.handwriting.mlkit",
        "org.fcitx.fcitx5.android.plugin.handwriting.mlkit.debug",
    )

    class PluginServiceConnection(
        private val pluginId: String,
        private val onDied: (PluginServiceConnection) -> Unit
    ) : ServiceConnection {
        @Volatile
        private var messenger: Messenger? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service)
            Timber.d("Plugin connected: $pluginId")
        }

        // may re-connect in the future
        override fun onServiceDisconnected(name: ComponentName) {
            messenger = null
            Timber.d("Plugin disconnected: $pluginId")
        }

        // will never receive another connection
        override fun onBindingDied(name: ComponentName?) {
            onDied.invoke(this)
            Timber.d("Plugin binding died: $pluginId")
        }

        fun sendMessage(message: Message) {
            try {
                messenger?.send(message)
            } catch (e: Throwable) {
                Timber.w("Cannot send message to plugin: $pluginId")
                Timber.w(e)
            }
        }
    }

    private val connectionLock = Any()
    private val connections = mutableMapOf<String, PluginServiceConnection>()

    private fun isTrustedPlugin(descriptor: PluginDescriptor): Boolean = try {
        evaluatePluginTrust(
            descriptor.packageName,
            BuildConfig.DEBUG,
            appContext.packageManager.checkSignatures(
                appContext.packageName,
                descriptor.packageName,
            ),
        ) == null
    } catch (failure: Exception) {
        Timber.w(failure, "Failed to verify plugin ${descriptor.packageName}")
        false
    }

    private fun connectPlugin(descriptor: PluginDescriptor): Boolean = synchronized(connectionLock) {
        if (connections.containsKey(descriptor.runtimeId)) return@synchronized true
        if (!isTrustedPlugin(descriptor)) {
            Timber.w("Refusing to bind untrusted plugin ${descriptor.packageName}")
            return@synchronized false
        }
        val connection = PluginServiceConnection(descriptor.runtimeId) { deadConnection ->
            disconnectPlugin(descriptor.runtimeId, deadConnection)
        }
        try {
            val result = appContext.bindService(
                Intent(PLUGIN_SERVICE_ACTION).apply { setPackage(descriptor.packageName) },
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (result) {
                connections[descriptor.runtimeId] = connection
                Timber.d("Bound to plugin ${descriptor.name}")
                return@synchronized true
            }
        } catch (failure: Exception) {
            Timber.w(failure, "Cannot bind to plugin ${descriptor.name}")
        }
        Timber.w("No service found for plugin: ${descriptor.name}")
        false
    }

    fun connectAll() {
        DataManager.getLoadedPlugins().forEach {
            if (it.hasService && it.packageName !in builtInReplacementPackages) {
                connectPlugin(it)
            }
        }
    }

    private fun disconnectPlugin(
        runtimeId: String,
        expectedConnection: PluginServiceConnection? = null,
    ) {
        val connection = synchronized(connectionLock) {
            val currentConnection = connections[runtimeId] ?: return@synchronized null
            if (expectedConnection != null && currentConnection !== expectedConnection) {
                return@synchronized null
            }
            connections.remove(runtimeId)
        } ?: return
        appContext.unbindService(connection)
        Timber.d("Unbound plugin: $runtimeId")
    }

    fun disconnectPackage(packageName: String) {
        disconnectPlugin(packageName)
    }

    fun connectPackage(packageName: String): Boolean {
        if (packageName in builtInReplacementPackages) {
            return false
        }
        val descriptor = DataManager.getLoadedPlugins()
            .firstOrNull { it.packageName == packageName && it.hasService }
            ?: return false
        return connectPlugin(descriptor)
    }

    fun disconnectAll() {
        val activeConnections = synchronized(connectionLock) {
            connections.toList().also { connections.clear() }
        }
        activeConnections.forEach { (runtimeId, connection) ->
            appContext.unbindService(connection)
            Timber.d("Unbound plugin: $runtimeId")
        }
    }

    fun sendMessage(message: Message) {
        synchronized(connectionLock) { connections.values.toList() }
            .forEach { it.sendMessage(message) }
    }
}
