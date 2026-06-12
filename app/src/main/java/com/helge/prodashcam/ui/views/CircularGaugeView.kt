package com.helge.prodashcam.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CircularGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var value: Float = 0f
    private var maxValue: Float = 240f
    private var label: String = ""
    private var unit: String = ""

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Pro Blue default
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 64f
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val padding = 20f
        rect.set(padding, padding, width - padding, height - padding)

        // Draw background arc (240 degrees)
        canvas.drawArc(rect, 150f, 240f, false, backgroundPaint)

        // Draw progress arc
        val sweepAngle = (value / maxValue) * 240f
        canvas.drawArc(rect, 150f, sweepAngle, false, progressPaint)

        // Draw value
        canvas.drawText("${value.toInt()}", width / 2f, height / 2f + 10f, valuePaint)

        // Draw unit/label
        canvas.drawText("$unit", width / 2f, height / 2f + 40f, labelPaint)
        canvas.drawText(label, width / 2f, height - 30f, labelPaint)
    }

    fun setValue(newValue: Float) {
        value = newValue.coerceIn(0f, maxValue)
        invalidate()
    }

    fun setMaxValue(max: Float) {
        maxValue = max
        invalidate()
    }

    fun setLabel(newLabel: String) {
        label = newLabel
        invalidate()
    }

    fun setUnit(newUnit: String) {
        unit = newUnit
        invalidate()
    }

    fun setProgressColor(colorInt: Int) {
        progressPaint.color = colorInt
        invalidate()
    }
}
