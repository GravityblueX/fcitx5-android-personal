/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.handwriting.mlkit

import android.app.Activity
import android.os.Bundle

/**
 * Clears Android's stopped-package state after installation without showing plugin UI.
 *
 * Some vendor systems reject a cross-package service bind until the user has explicitly
 * launched an activity from the installed package.
 */
class ActivationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
