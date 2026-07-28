/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view
import kotlin.math.roundToInt

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val toolButtons = mutableListOf<ToolButton>()

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(BUTTON_SIZE_DP)
        root.addView(it, FlexboxLayout.LayoutParams(size, size))
        toolButtons.add(it)
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move).apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val floatingKeyboardButton = toolButton(R.drawable.ic_mdi_keyboard_close_24)

    val moreButton = toolButton(R.drawable.ic_baseline_more_horiz_24).apply {
        contentDescription = ctx.getString(R.string.status_area)
    }

    fun setContentScale(scale: Float) {
        val buttonSize = (ctx.dp(BUTTON_SIZE_DP) * scale)
            .roundToInt()
            .coerceAtLeast(ctx.dp(MIN_BUTTON_SIZE_DP))
        toolButtons.forEach {
            it.setContentScale(scale)
            it.layoutParams = (it.layoutParams as FlexboxLayout.LayoutParams).apply {
                width = buttonSize
                height = buttonSize
            }
        }
    }

    private companion object {
        const val BUTTON_SIZE_DP = 40
        const val MIN_BUTTON_SIZE_DP = 30
    }
}
