/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import org.fcitx.fcitx5.android.common.handwriting.HandwritingInkPoint
import org.fcitx.fcitx5.android.common.handwriting.HandwritingInkStroke
import splitties.dimensions.dp
import kotlin.math.hypot

@SuppressLint("ViewConstructor")
class HandwritingCanvas(
    context: Context,
    strokeColor: Int,
    private val onStrokeFinished: () -> Unit,
) : View(context) {

    private data class DrawnStroke(
        val ink: MutableList<HandwritingInkPoint>,
        val path: Path,
        var lastDrawX: Float,
        var lastDrawY: Float,
        var hasDrawnSegment: Boolean = false,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3).toFloat()
    }

    private val strokes = mutableListOf<DrawnStroke>()
    private var activeStroke: DrawnStroke? = null
    private var contentScale = 1f

    init {
        isClickable = true
    }

    fun setContentScale(scale: Float) {
        if (contentScale == scale) return
        contentScale = scale
        paint.strokeWidth = dp(3).toFloat() * scale.coerceAtLeast(0.5f)
        invalidate()
    }

    fun clear() {
        activeStroke = null
        strokes.clear()
        invalidate()
    }

    fun undo(): Boolean {
        if (activeStroke != null || strokes.isEmpty()) return false
        strokes.removeAt(strokes.lastIndex)
        invalidate()
        return true
    }

    val hasInk: Boolean
        get() = strokes.isNotEmpty()

    fun snapshot(): List<HandwritingInkStroke> =
        strokes.map { HandwritingInkStroke(it.ink.toList()) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { canvas.drawPath(it.path, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val stroke = DrawnStroke(
                    ink = mutableListOf(),
                    path = Path(),
                    lastDrawX = event.x,
                    lastDrawY = event.y,
                )
                strokes += stroke
                activeStroke = stroke
                appendPoint(stroke, event.x, event.y, event.eventTime, event.pressure, event.getToolType(0))
                stroke.path.moveTo(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = activeStroke ?: return false
                for (i in 0 until event.historySize) {
                    val x = event.getHistoricalX(0, i)
                    val y = event.getHistoricalY(0, i)
                    appendPoint(
                        stroke,
                        x,
                        y,
                        event.getHistoricalEventTime(i),
                        event.getHistoricalPressure(0, i),
                        event.getToolType(0),
                    )
                    appendPathPoint(stroke, x, y)
                }
                appendPoint(
                    stroke,
                    event.x,
                    event.y,
                    event.eventTime,
                    event.pressure,
                    event.getToolType(0),
                )
                appendPathPoint(stroke, event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val stroke = activeStroke ?: return false
                appendPoint(
                    stroke,
                    event.x,
                    event.y,
                    event.eventTime,
                    event.pressure,
                    event.getToolType(0),
                )
                appendPathPoint(stroke, event.x, event.y)
                finishPath(stroke, event.x, event.y)
                activeStroke = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                invalidate()
                onStrokeFinished()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activeStroke?.let { strokes.remove(it) }
                activeStroke = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun appendPoint(
        stroke: DrawnStroke,
        x: Float,
        y: Float,
        timestampMillis: Long,
        pressure: Float,
        toolType: Int,
    ) {
        stroke.ink += HandwritingInkPoint(x, y, timestampMillis, pressure, toolType)
    }

    private fun appendPathPoint(stroke: DrawnStroke, x: Float, y: Float) {
        val dx = x - stroke.lastDrawX
        val dy = y - stroke.lastDrawY
        val tolerance = dp(1).toFloat() * contentScale.coerceAtLeast(0.5f)
        if (hypot(dx, dy) < tolerance) return
        stroke.path.quadTo(
            stroke.lastDrawX,
            stroke.lastDrawY,
            (stroke.lastDrawX + x) / 2f,
            (stroke.lastDrawY + y) / 2f,
        )
        stroke.lastDrawX = x
        stroke.lastDrawY = y
        stroke.hasDrawnSegment = true
    }

    private fun finishPath(stroke: DrawnStroke, x: Float, y: Float) {
        if (stroke.hasDrawnSegment || x != stroke.lastDrawX || y != stroke.lastDrawY) {
            stroke.path.lineTo(x, y)
        } else {
            // Keep a tap visible as a round dot.
            stroke.path.lineTo(x + 0.01f, y)
        }
    }
}
