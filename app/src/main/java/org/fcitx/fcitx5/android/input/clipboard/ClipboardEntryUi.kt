/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp
import kotlin.math.roundToInt

class ClipboardEntryUi(
    override val ctx: Context,
    private val theme: Theme,
    private val radius: Float
) : Ui {

    val textView = textView {
        minLines = 1
        maxLines = 4
        textSize = 14f
        setPaddingDp(8, 4, 8, 4)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.keyTextColor)
    }
    private val baseTextSize = textView.textSize

    val pin = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
            setTint(theme.altKeyTextColor)
            setAlpha(0.3f)
        }
    }

    val layout = constraintLayout {
        add(textView, lParams(matchParent, wrapContent) {
            centerVertically()
        })
        add(pin, lParams(dp(12), dp(12)) {
            bottomOfParent(dp(2))
            endOfParent(dp(2))
        })
    }

    private val rippleMask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.WHITE)
    }

    private val entryBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.clipboardEntryColor)
    }

    override val root = CustomGestureView(ctx).apply {
        isClickable = true
        minimumHeight = dp(30)
        foreground = RippleDrawable(
            ColorStateList.valueOf(theme.keyPressHighlightColor), null, rippleMask
        )
        background = entryBackground
        add(layout, lParams(matchParent, matchParent))
    }

    fun setEntry(text: String, pinned: Boolean) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
    }

    fun setContentScale(scale: Float) {
        val scaled = scale.coerceIn(0f, 1f)
        textView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSize * scaled)
            setPadding(
                (ctx.dp(8) * scaled).roundToInt(),
                (ctx.dp(4) * scaled).roundToInt(),
                (ctx.dp(8) * scaled).roundToInt(),
                (ctx.dp(4) * scaled).roundToInt()
            )
        }
        pin.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = (ctx.dp(12) * scaled).roundToInt()
            height = (ctx.dp(12) * scaled).roundToInt()
            rightMargin = (ctx.dp(2) * scaled).roundToInt()
            bottomMargin = (ctx.dp(2) * scaled).roundToInt()
        }
        root.minimumHeight = (ctx.dp(30) * scaled).roundToInt()
        rippleMask.cornerRadius = radius * scaled
        entryBackground.cornerRadius = radius * scaled
    }
}
