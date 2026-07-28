/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add

class CandidateUi(override val ctx: Context, theme: Theme, private val horizontalView: View) : Ui {

    val clearButton = ToolButton(ctx, R.drawable.ic_mdi_close_circle_24, theme).apply {
        contentDescription = ctx.getString(R.string.clear)
        setBoundedPressHighlightColor(theme.keyPressHighlightColor)
    }

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
    }

    override val root = ctx.constraintLayout {
        add(clearButton, lParams(dp(CLEAR_BUTTON_WIDTH_DP), dp(CLEAR_BUTTON_HEIGHT_DP)) {
            centerVertically()
            startOfParent()
        })
        add(expandButton, lParams(dp(40)) {
            centerVertically()
            endOfParent()
        })
        add(horizontalView, lParams {
            centerVertically()
            after(clearButton)
            before(expandButton)
        })
    }

    fun setContentScale(scale: Float) {
        clearButton.setContentScale(scale * CLEAR_ICON_SCALE)
        expandButton.setContentScale(scale)
    }

    private companion object {
        const val CLEAR_BUTTON_WIDTH_DP = 28
        const val CLEAR_BUTTON_HEIGHT_DP = 28
        const val CLEAR_ICON_SCALE = 1.75f
    }
}
