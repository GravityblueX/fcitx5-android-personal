/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import androidx.navigation.NavController
import androidx.navigation.navOptions
import org.fcitx.fcitx5.android.R

fun <T : Any> NavController.navigateWithAnim(route: T) {
    navigate(route, navOptions {
        anim {
            enter = R.animator.settings_forward_enter
            exit = R.animator.settings_forward_exit
            popEnter = R.animator.settings_back_enter
            popExit = R.animator.settings_back_exit
        }
    })
}
