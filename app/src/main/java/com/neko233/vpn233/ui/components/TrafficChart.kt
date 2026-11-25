package com.neko233.vpn233.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun TrafficSparkline(
    points: List<Pair<Long, Long>>,
    downColor: Color,
    upColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val w = size.width
        val h = size.height
        // Subtle horizontal guide lines
        drawLine(gridColor, Offset(0f, h * 0.33f), Offset(w, h * 0.33f), strokeWidth = 1f)
        drawLine(gridColor, Offset(0f, h * 0.66f), Offset(w, h * 0.66f), strokeWidth = 1f)

        if (points.size < 2) {
            drawLine(downColor.copy(alpha = 0.25f), Offset(0f, h - 4f), Offset(w, h - 4f), strokeWidth = 2f)
            return@Canvas
        }

        val maxVal = points.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1024L).toFloat()
        val count = points.size
        val dx = w / (count - 1).coerceAtLeast(1)

        val downPath = Path()
        val fillPath = Path()
        val upPath = Path()

        points.forEachIndexed { idx, (up, down) ->
            val x = idx * dx
            val yDown = h - 4f - (down / maxVal) * (h - 10f)
            val yUp = h - 4f - (up / maxVal) * (h - 10f)
            if (idx == 0) {
                downPath.moveTo(x, yDown)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, yDown)
                upPath.moveTo(x, yUp)
            } else {
                downPath.lineTo(x, yDown)
                fillPath.lineTo(x, yDown)
                upPath.lineTo(x, yUp)
            }
            if (idx == count - 1) {
                fillPath.lineTo(x, h)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(downColor.copy(alpha = 0.32f), downColor.copy(alpha = 0.02f))
            )
        )
        drawPath(
            path = upPath,
            color = upColor.copy(alpha = 0.85f),
            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
        )
        drawPath(
            path = downPath,
            color = downColor,
            style = Stroke(width = 3.2f, cap = StrokeCap.Round)
        )
    }
}
