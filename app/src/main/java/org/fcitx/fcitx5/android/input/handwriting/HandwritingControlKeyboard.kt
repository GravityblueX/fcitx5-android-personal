/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.BackspaceKey
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef

/**
 * Standard key-cap controls placed at the right side of the handwriting canvas.
 */
@SuppressLint("ViewConstructor")
class HandwritingControlKeyboard(
    context: Context,
    theme: Theme,
    private val onUndo: () -> Unit,
    private val onClear: () -> Unit,
) : BaseKeyboard(context, theme, Layout) {

    private class CommandKey(
        @DrawableRes icon: Int,
        command: String,
    ) : KeyDef(
        Appearance.Image(
            src = icon,
            percentWidth = 1f,
            variant = Appearance.Variant.Alternative,
        ),
        setOf(Behavior.Press(KeyAction.LayoutSwitchAction(command))),
    )

    private companion object {
        const val UndoCommand = "handwriting-undo"
        const val ClearCommand = "handwriting-clear"

        val Layout = listOf(
            listOf(CommandKey(R.drawable.ic_baseline_undo_24, UndoCommand)),
            listOf(CommandKey(R.drawable.ic_baseline_delete_sweep_24, ClearCommand)),
            listOf(BackspaceKey(percentWidth = 1f)),
        )
    }

    protected override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        when ((action as? KeyAction.LayoutSwitchAction)?.act) {
            UndoCommand -> onUndo()
            ClearCommand -> onClear()
            else -> super.onAction(action, source)
        }
    }
}
