package ru.doronin.healthconnector.floors

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

class FloorDayChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var date: LocalDate = LocalDate.now(ZoneId.systemDefault())
    private var points: List<PhoneFloorStore.HistoryPoint> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
    }

    fun setData(date: LocalDate, points: List<PhoneFloorStore.HistoryPoint>) {
        this.date = date
        this.points = points.sortedBy { it.timestampEpochMs }
        contentDescription = if (points.isEmpty()) {
            "График набора высоты: сегодня подъёмов пока нет"
        } else {
            val last = points.last()
            "График набора высоты: ${last.floors} этажей, ${last.elevationMeters.toInt()} метров"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        linePaint.color = primary
        pointPaint.color = primary
        gridPaint.color = outline
        labelPaint.color = onSurface
        labelPaint.alpha = 170

        val left = paddingLeft + dp(4f)
        val right = width - paddingRight - dp(4f)
        val top = paddingTop + dp(8f)
        val bottom = height - paddingBottom - dp(24f)
        if (right <= left || bottom <= top) return

        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        val labels = listOf("00", "06", "12", "18", "24")
        labels.forEachIndexed { index, label ->
            val x = left + (right - left) * index / 4f
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val textWidth = labelPaint.measureText(label)
            canvas.drawText(label, x - textWidth / 2f, height - paddingBottom.toFloat(), labelPaint)
        }

        if (points.isEmpty()) {
            val text = "Подъёмов пока нет"
            val textWidth = labelPaint.measureText(text)
            canvas.drawText(text, left + (right - left - textWidth) / 2f, top + (bottom - top) / 2f, labelPaint)
            return
        }

        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val maxElevation = max(3.0, points.maxOfOrNull { it.elevationMeters } ?: 3.0)

        fun x(timestamp: Long): Float {
            val fraction = ((timestamp - dayStart).toDouble() / (dayEnd - dayStart).toDouble()).coerceIn(0.0, 1.0)
            return left + (right - left) * fraction.toFloat()
        }

        fun y(elevation: Double): Float {
            val fraction = (elevation / maxElevation).coerceIn(0.0, 1.0)
            return bottom - (bottom - top) * fraction.toFloat()
        }

        val path = Path().apply {
            moveTo(left, bottom)
            points.forEach { point -> lineTo(x(point.timestampEpochMs), y(point.elevationMeters)) }
        }
        canvas.drawPath(path, linePaint)
        points.forEach { point ->
            canvas.drawCircle(x(point.timestampEpochMs), y(point.elevationMeters), dp(3f), pointPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
