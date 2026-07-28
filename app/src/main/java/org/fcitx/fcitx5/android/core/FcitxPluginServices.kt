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

    class PluginServiceConnection(
        private val pluginId: String,
        private val onDied: () -> Unit
    ) : ServiceConnection {
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
            onDied.invoke()
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

    private val connections = mutableMapOf<String, PluginServiceConnection>()

    private fun connectPlugin(descriptor: PluginDescriptor) {
        for (action in compatiblePluginServiceActions) {
            val connection = PluginServiceConnection(descriptor.name) {
                disconnectPlugin(descriptor.name)
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
                    return
                }
            } catch (e: Exception) {
                // Official service plugins may require the official app's signature permission.
                Timber.w("Cannot bind to plugin ${descriptor.name} with action $action")
                Timber.w(e)
            }
        }
        Timber.w("No compatible service found for plugin: ${descriptor.name}")
    }

    fun connectAll() {
        DataManager.getLoadedPlugins().forEach {
            if (it.hasService && !connections.containsKey(it.name)) {
                connectPlugin(it)
            }
        }
    }

    private fun disconnectPlugin(name: String) {
        connections.remove(name)?.also {
            appContext.unbindService(it)
            Timber.d("Unbound plugin: $name")
        }
    }

    fun disconnectAll() {
        connections.forEach { (name, connection) ->
            appContext.unbindService(connection)
            Timber.d("Unbound plugin: $name")
        }
        connections.clear()
    }

    fun sendMessage(message: Message) {
        connections.forEach { (_, conn) ->
            conn.sendMessage(message)
        }
    }
}
