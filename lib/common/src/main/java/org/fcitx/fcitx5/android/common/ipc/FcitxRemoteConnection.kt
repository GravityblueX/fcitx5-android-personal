/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

fun Context.bindFcitxRemoteService(
    mainApplicationId: String,
    onDisconnect: () -> Unit = {},
    onConnected: (IFcitxRemoteService) -> Unit
): FcitxRemoteConnection {
    val connection = FcitxRemoteConnection(onConnected, onDisconnect)
    connection.setBound(
        bindService(
            Intent("$mainApplicationId.IPC").apply {
                setPackage(mainApplicationId)
            },
            connection,
            Context.BIND_AUTO_CREATE
        )
    )
    return connection
}

open class FcitxRemoteConnection(
    private val onConnected: (IFcitxRemoteService) -> Unit,
    private val onDisconnected: () -> Unit
) : ServiceConnection {
    private val bindingLock = Any()

    private var isBound = false

    var remoteService: IFcitxRemoteService? = null
        private set

    internal fun setBound(bound: Boolean) = synchronized(bindingLock) {
        isBound = bound
    }

    fun unbind(context: Context) {
        val wasBound = synchronized(bindingLock) {
            if (!isBound) return
            isBound = false
            true
        }
        if (wasBound) {
            context.unbindService(this)
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        IFcitxRemoteService.Stub.asInterface(service).let {
            remoteService = it
            onConnected(it)
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        onDisconnected()
    }

}