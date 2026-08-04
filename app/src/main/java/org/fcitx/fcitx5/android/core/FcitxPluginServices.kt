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
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

object FcitxPluginServices {

    const val PLUGIN_SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SERVICE"
    private const val OFFICIAL_PLUGIN_SERVICE_ACTION =
        "org.fcitx.fcitx5.android.plugin.SERVICE"
    private const val OFFICIAL_DEBUG_PLUGIN_SERVICE_ACTION =
        "org.fcitx.fcitx5.android.debug.plugin.SERVICE"

    private val compatiblePluginServiceActions = linkedSetOf(
        PLUGIN_SERVICE_ACTION,
        OFFICIAL_PLUGIN_SERVICE_ACTION,
        OFFICIAL_DEBUG_PLUGIN_SERVICE_ACTION
    )
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

    private fun connectPlugin(descriptor: PluginDescriptor): Boolean = synchronized(connectionLock) {
        if (connections.containsKey(descriptor.name)) return@synchronized true
        for (action in compatiblePluginServiceActions) {
            val connection = PluginServiceConnection(descriptor.name) { deadConnection ->
                disconnectPlugin(descriptor.name, deadConnection)
            }
            try {
                val result = appContext.bindService(
                    Intent(action).apply { setPackage(descriptor.packageName) },
                    connection,
                    Context.BIND_AUTO_CREATE
                )
                if (result) {
                    connections[descriptor.name] = connection
                    Timber.d("Bound to plugin ${descriptor.name} with action $action")
                    return@synchronized true
                }
                appContext.unbindService(connection)
            } catch (e: Exception) {
                // Official service plugins may require the official app's signature permission.
                Timber.w("Cannot bind to plugin ${descriptor.name} with action $action")
                Timber.w(e)
            }
        }
        Timber.w("No compatible service found for plugin: ${descriptor.name}")
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
        name: String,
        expectedConnection: PluginServiceConnection? = null,
    ) {
        val connection = synchronized(connectionLock) {
            val currentConnection = connections[name] ?: return@synchronized null
            if (expectedConnection != null && currentConnection !== expectedConnection) {
                return@synchronized null
            }
            connections.remove(name)
        } ?: return
        appContext.unbindService(connection)
        Timber.d("Unbound plugin: $name")
    }

    fun disconnectPackage(packageName: String) {
        DataManager.getLoadedPlugins()
            .firstOrNull { it.packageName == packageName }
            ?.let { disconnectPlugin(it.name) }
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
        activeConnections.forEach { (name, connection) ->
            appContext.unbindService(connection)
            Timber.d("Unbound plugin: $name")
        }
    }

    fun sendMessage(message: Message) {
        synchronized(connectionLock) { connections.values.toList() }
            .forEach { it.sendMessage(message) }
    }
}
