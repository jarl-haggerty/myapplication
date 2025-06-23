package org.pesaran.sleeve

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.lang.Double.isInfinite
import kotlin.Double.Companion.NEGATIVE_INFINITY
import kotlin.Double.Companion.POSITIVE_INFINITY
import kotlin.math.max
import kotlin.math.min

class SignalPlot(val name: String) {
    var path1 = Path()
    var path2 = Path()
    var time = 0L
    var bottom = POSITIVE_INFINITY
    var top = NEGATIVE_INFINITY
    var height = 0.0

    fun addSignal(y: Double, sampleIntervalNs: Long) {
        if (time < 10e9) {
            if(path1.isEmpty) {
                path1.moveTo(time.toFloat(), y.toFloat())
            } else {
                path1.lineTo(time.toFloat(), y.toFloat())
            }
        } else if(time < 20e9) {
            if(path2.isEmpty) {
                path2.moveTo((time-10e9).toFloat(), y.toFloat())
            } else {
                path2.lineTo((time-10e9).toFloat(), y.toFloat())
            }
        } else {
            path1 = path2
            path2 = Path()
            time = 10000000000L
            addSignal(y, sampleIntervalNs)
        }
        bottom = min(bottom, y)
        top = max(top, y)
        time += sampleIntervalNs
    }

    fun draw(scope: DrawScope, textMeasurer: TextMeasurer, bounds: Rect, color: Color) {
        if(isInfinite(bottom)) {
            return
        }
        val scaleX = bounds.width / 10e9
        val scaleY = bounds.height/(top - bottom)
        scope.apply {
            val titleMeasure = textMeasurer.measure(name)
            drawText(textMeasurer, name, bounds.topRight - Offset(titleMeasure.size.width.toFloat(), 0f), TextStyle(color=Color.White))

            drawText(textMeasurer, top.toString(), bounds.topLeft, TextStyle(color=Color.White))
            val text = bottom.toString()
            val measured = textMeasurer.measure(text)
            drawText(textMeasurer, text, bounds.bottomLeft - Offset(0F,
                measured.size.height.toFloat()
            ), TextStyle(color=Color.White))

            translate (bounds.left, bounds.top + bounds.height*.95f) {
                scale(scaleX.toFloat(), -scaleY.toFloat()*.9f, Offset(0F, 0F)) {
                    translate(10.0E9F - time, -bottom.toFloat()) {
                        drawPath(path1, color, style=Stroke())
                    }
                    translate(20.0E9F - time, -bottom.toFloat()) {
                        drawPath(path2, color, style=Stroke())
                    }
                }
            }
        }
    }
}
