package com.example.voltify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class VoltifyComparisonChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var labels: List<String> = emptyList()
    private var valuesA: List<Double> = emptyList()
    private var valuesB: List<Double> = emptyList()
    private var nameA: String = "A"
    private var nameB: String = "B"

    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.voltify_bright_blue)
    }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.voltify_cyan)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.voltify_text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.voltify_text_primary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.LEFT
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.voltify_text_muted)
        alpha = 70
        strokeWidth = resources.displayMetrics.density
    }

    fun setData(
        newLabels: List<String>,
        newValuesA: List<Double>,
        newValuesB: List<Double>,
        seriesAName: String,
        seriesBName: String
    ) {
        labels = newLabels
        valuesA = newValuesA
        valuesB = newValuesB
        nameA = seriesAName
        nameB = seriesBName
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty() || valuesA.isEmpty() || valuesB.isEmpty()) return

        val density = resources.displayMetrics.density
        val left = paddingLeft + 12f * density
        val right = width - paddingRight - 12f * density
        val top = paddingTop + 30f * density
        val bottom = height - paddingBottom - 30f * density
        if (bottom <= top || right <= left) return

        canvas.drawRect(left, 7f * density, left + 10f * density, 17f * density, paintA)
        canvas.drawText(nameA.take(18), left + 15f * density, 17f * density, legendPaint)
        val secondX = left + (right - left) / 2f
        canvas.drawRect(secondX, 7f * density, secondX + 10f * density, 17f * density, paintB)
        canvas.drawText(nameB.take(18), secondX + 15f * density, 17f * density, legendPaint)

        val maxValue = max((valuesA + valuesB).maxOrNull() ?: 1.0, 0.01)
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, top + (bottom - top) / 2f, right, top + (bottom - top) / 2f, gridPaint)

        val count = minOf(labels.size, valuesA.size, valuesB.size)
        val slot = (right - left) / count
        val barWidth = slot * 0.28f
        repeat(count) { index ->
            val xCenter = left + slot * index + slot / 2f
            val ratioA = (valuesA[index] / maxValue).toFloat().coerceIn(0f, 1f)
            val ratioB = (valuesB[index] / maxValue).toFloat().coerceIn(0f, 1f)
            val topA = bottom - (bottom - top) * ratioA
            val topB = bottom - (bottom - top) * ratioB

            canvas.drawRoundRect(
                xCenter - barWidth - 2f * density,
                topA,
                xCenter - 2f * density,
                bottom,
                6f * density,
                6f * density,
                paintA
            )
            canvas.drawRoundRect(
                xCenter + 2f * density,
                topB,
                xCenter + barWidth + 2f * density,
                bottom,
                6f * density,
                6f * density,
                paintB
            )
            canvas.drawText(labels[index], xCenter, height - 8f * density, textPaint)
        }
    }
}
