package com.alex.obstaclealert.ui.home
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.alex.obstaclealert.R

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        color = context.getColor(R.color.bounding_box)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textPaint = Paint().apply {
        color = context.getColor(R.color.bounding_box)
        textSize = 48f
        style = Paint.Style.FILL
    }

    private var results: List<HomeFragment.DetectionResult> = emptyList()

    fun setResults(results: List<HomeFragment.DetectionResult>) {
        this.results = results
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (result in results) {
            // Draw bounding box
            canvas.drawRect(result.location, paint)

            // Draw category and score
            val text = "${result.category}: ${(result.score * 100).toInt()}% -- ${"%.2f".format(result.distancePredict)}m"
            canvas.drawText(text, result.location.left, result.location.top - 10, textPaint)

        }
    }
}