/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.widget.Button
import android.app.AlertDialog as FrameworkAlertDialog
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog

val FrameworkAlertDialog.positiveButton: Button
    get() = getButton(FrameworkAlertDialog.BUTTON_POSITIVE)

val FrameworkAlertDialog.negativeButton: Button
    get() = getButton(FrameworkAlertDialog.BUTTON_NEGATIVE)

val FrameworkAlertDialog.neutralButton: Button
    get() = getButton(FrameworkAlertDialog.BUTTON_NEUTRAL)

val AppCompatAlertDialog.positiveButton: Button
    get() = getButton(AppCompatAlertDialog.BUTTON_POSITIVE)

val AppCompatAlertDialog.negativeButton: Button
    get() = getButton(AppCompatAlertDialog.BUTTON_NEGATIVE)

val AppCompatAlertDialog.neutralButton: Button
    get() = getButton(AppCompatAlertDialog.BUTTON_NEUTRAL)

/**
 * Change positive button listener **AFTER** [AlertDialog.show] has been called.
 *
 * In the listener: `true` to dismiss the dialog; `false` to keep the dialog open.
 */
fun FrameworkAlertDialog.onPositiveButtonClick(
    l: FrameworkAlertDialog.() -> Boolean?
): FrameworkAlertDialog {
    positiveButton.setOnClickListener {
        if (l.invoke(this) == true) dismiss()
    }
    return this
}

fun AppCompatAlertDialog.onPositiveButtonClick(
    l: AppCompatAlertDialog.() -> Boolean?
): AppCompatAlertDialog {
    positiveButton.setOnClickListener {
        if (l.invoke(this) == true) dismiss()
    }
    return this
}

/**
 * Change negative button listener **AFTER** [AlertDialog.show] has been called.
 *
 * In the listener: `true` to dismiss the dialog; `false` to keep the dialog open.
 */
fun FrameworkAlertDialog.onNegativeButtonClick(
    l: FrameworkAlertDialog.() -> Boolean
): FrameworkAlertDialog {
    negativeButton.setOnClickListener {
        if (l.invoke(this)) dismiss()
    }
    return this
}

fun AppCompatAlertDialog.onNegativeButtonClick(
    l: AppCompatAlertDialog.() -> Boolean
): AppCompatAlertDialog {
    negativeButton.setOnClickListener {
        if (l.invoke(this)) dismiss()
    }
    return this
}

/**
 * Change neutral button listener **AFTER** [AlertDialog.show] has been called.
 *
 * In the listener: `true` to dismiss the dialog; `false` to keep the dialog open.
 */
fun FrameworkAlertDialog.onNeutralButtonClick(
    l: FrameworkAlertDialog.() -> Boolean
): FrameworkAlertDialog {
    neutralButton.setOnClickListener {
        if (l.invoke(this)) dismiss()
    }
    return this
}

fun AppCompatAlertDialog.onNeutralButtonClick(
    l: AppCompatAlertDialog.() -> Boolean
): AppCompatAlertDialog {
    neutralButton.setOnClickListener {
        if (l.invoke(this)) dismiss()
    }
    return this
}
