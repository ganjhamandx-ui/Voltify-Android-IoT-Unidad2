package com.example.voltify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class VoltifyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var labels: List<String> = emptyList()
    private var values: List<Double> = emptyList()
    private var comparisonMode = false

    private val paints = mutableListOf<Paint>()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val palette = listOf(
            R.color.voltify_bright_blue,
            R.color.voltify_cyan,
            R.color.voltify_success,
            R.color.voltify_purple,
            R.color.voltify_warning,
            R.color.voltify_orange,
            R.color.voltify_pink
        )
        palette.forEach { colorRes ->
            paints += Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, colorRes)
            }
        }
        textPaint.color = ContextCompat.getColor(context, R.color.voltify_text_secondary)
        textPaint.textSize = 11f * resources.displayMetrics.scaledDensity
        textPaint.textAlign = Paint.Align.CENTER
        gridPaint.color = ContextCompat.getColor(context, R.color.voltify_text_muted)
        gridPaint.alpha = 70
        gridPaint.strokeWidth = resources.displayMetrics.density
    }

    fun setData(newLabels: List<String>, newValues: List<Double>, comparison: Boolean = false) {
        labels = newLabels
        values = newValues
        comparisonMode = comparison
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val density = resources.displayMetrics.density
        val left = paddingLeft + 12f * density
        val right = width - paddingRight - 12f * density
        val top = paddingTop + 12f * density
        val bottom = height - paddingBottom - 30f * density
        if (bottom <= top || right <= left) return

        val maxValue = max(values.maxOrNull() ?: 1.0, 0.01)
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, top + (bottom - top) / 2f, right, top + (bottom - top) / 2f, gridPaint)

        val slot = (right - left) / values.size
        val barWidth = slot * 0.55f
        values.forEachIndexed { index, value ->
            val xCenter = left + slot * index + slot / 2f
            val ratio = (value / maxValue).toFloat().coerceIn(0f, 1f)
            val barTop = bottom - (bottom - top) * ratio

            // Comparación: Actual azul / Anterior cian.
            // Tendencias: paleta Voltify rotativa para diferenciar visualmente cada período.
            val paint = if (comparisonMode) {
                if (index % 2 == 0) paints[0] else paints[1]
            } else {
                paints[index % paints.size]
            }

            canvas.drawRoundRect(
                xCenter - barWidth / 2f,
                barTop,
                xCenter + barWidth / 2f,
                bottom,
                8f * density,
                8f * density,
                paint
            )
            if (index < labels.size) {
                canvas.drawText(
                    labels[index],
                    xCenter,
                    height - 8f * density,
                    textPaint
                )
            }
        }
    }
}
