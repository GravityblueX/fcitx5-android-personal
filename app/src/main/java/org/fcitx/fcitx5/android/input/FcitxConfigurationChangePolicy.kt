/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.pm.ActivityInfo

internal object FcitxConfigurationChangePolicy {

    fun requiresReset(configDiff: Int): Boolean =
        configDiff and ActivityInfo.CONFIG_UI_MODE.inv() != 0
}
