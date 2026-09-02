package com.wzvideni.androidmcp.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.wzvideni.androidmcp.model.UiNode

object SetOfMarkAnnotator {

    private val COLORS = intArrayOf(
        0xFFE91E63.toInt(), // Pink
        0xFF9C27B0.toInt(), // Purple
        0xFF2196F3.toInt(), // Blue
        0xFF009688.toInt(), // Teal
        0xFF4CAF50.toInt(), // Green
        0xFFFF9800.toInt(), // Orange
        0xFFF44336.toInt(), // Red
        0xFF3F51B5.toInt()  // Indigo
    )

    fun annotate(bitmap: Bitmap, elements: List<UiNode>): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val badgeBgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val textBounds = Rect()

        for ((index, node) in elements.withIndex()) {
            val color = COLORS[index % COLORS.size]
            borderPaint.color = color
            badgeBgPaint.color = color

            val rect = RectF(
                node.bounds.left.toFloat(),
                node.bounds.top.toFloat(),
                node.bounds.right.toFloat(),
                node.bounds.bottom.toFloat()
            )

            // Draw bounding box
            canvas.drawRect(rect, borderPaint)

            // Draw badge
            val label = node.id.toString()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val padding = 6f
            val badgeWidth = textBounds.width() + padding * 2
            val badgeHeight = textBounds.height() + padding * 2

            val badgeX = rect.left.coerceAtLeast(0f)
            val badgeY = (rect.top - badgeHeight).coerceAtLeast(0f)

            val badgeRect = RectF(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight)
            canvas.drawRoundRect(badgeRect, 4f, 4f, badgeBgPaint)

            canvas.drawText(
                label,
                badgeX + padding,
                badgeY + badgeHeight - padding,
                textPaint
            )
        }

        return mutable
    }
}
