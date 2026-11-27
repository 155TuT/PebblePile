package com.pebblepile.android

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class Relief { Flat, Raised, Inset, Primary, Pressed, DangerPressed, Overlay }
private data class Shadow(val offset: Int, val blur: Int, val color: Color)
private val raised = listOf(Shadow(-6,14,Color.White), Shadow(6,14,Color(0xFFD6D7CF)))
private val inset = listOf(Shadow(-3,7,Color.White), Shadow(3,7,Color(0xFFD1D4C9)))
private val primary = listOf(Shadow(-4,10,Color.White), Shadow(4,10,Color(0xFFC6CCC0)))
private val pressed = listOf(Shadow(-3,6,Color(0xFF58765F)), Shadow(3,6,Color(0xFF263F2D)))
private val dangerPressed = listOf(Shadow(-3,6,Color.White.copy(alpha=.15f)), Shadow(3,6,Color.Black.copy(alpha=.15f)))
private val overlay = listOf(Shadow(-4,16,Color.White.copy(alpha=.5f)), Shadow(12,32,Color(0x33222C24)))

/** Render a small software mask (also works on API 26) and stretch only its straight edges and centre. */
internal fun Modifier.pebbleSurface(
    relief: Relief, radius: Dp = 16.dp, color: Color = PebbleVisuals.Sand, squareBottom: Boolean = false,
): Modifier = drawWithCache {
    val shadows = when (relief) {
        Relief.Raised -> raised
        Relief.Inset -> inset
        Relief.Primary -> primary
        Relief.Pressed -> pressed
        Relief.DangerPressed -> dangerPressed
        Relief.Overlay -> overlay
        Relief.Flat -> emptyList()
    }
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    val r = minOf(radius.toPx(), width / 2f, height / 2f)
    if (width < 1 || height < 1 || shadows.isEmpty()) {
        return@drawWithCache onDrawBehind { drawRoundRect(color, cornerRadius = CornerRadius(r)) }
    }
    val inner = relief == Relief.Inset || relief == Relief.Pressed || relief == Relief.DangerPressed
    val reach = ceil(shadows.maxOf { abs(it.offset) + it.blur * 1.6f } * density).toInt()
    val pad = if (inner) 0 else reach
    val edge = ceil(r).toInt() + reach
    val w = minOf(width, 2 * edge + 1)
    val h = minOf(height, 2 * edge + 1)
    val bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bounds = RectF(pad.toFloat(), pad.toFloat(), (pad+w).toFloat(), (pad+h).toFloat())
    val bottom = if (squareBottom) 0f else r
    val outline = Path().apply {
        addRoundRect(bounds, floatArrayOf(r,r,r,r,bottom,bottom,bottom,bottom), Path.Direction.CW)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun fill() {
        paint.maskFilter = null
        paint.color = color.toArgb()
        canvas.drawPath(outline, paint)
    }
    if (inner) {
        fill()
        canvas.clipPath(outline)
    }
    for (shadow in shadows) {
        val delta = shadow.offset * density
        val mask = Path(outline).apply { offset(delta, delta) }
        if (inner) {
            mask.fillType = Path.FillType.EVEN_ODD
            mask.addRect(-reach.toFloat(), -reach.toFloat(), w+reach.toFloat(), h+reach.toFloat(), Path.Direction.CW)
        }
        paint.color = shadow.color.toArgb()
        paint.maskFilter = BlurMaskFilter(shadow.blur * density * .85f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(mask, paint)
    }
    if (!inner) fill()
    val left = minOf(edge,w/2)
    val top = minOf(edge,h/2)
    val sx = intArrayOf(0, pad+left, pad+w-left, bitmap.width)
    val sy = intArrayOf(0, pad+top, pad+h-top, bitmap.height)
    val dx = floatArrayOf(-pad.toFloat(), left.toFloat(), size.width-left, size.width+pad)
    val dy = floatArrayOf(-pad.toFloat(), top.toFloat(), size.height-top, size.height+pad)
    val slices = buildList {
        for (y in 0..2) for (x in 0..2) {
            if (sx[x] < sx[x+1] && sy[y] < sy[y+1]) {
                add(Rect(sx[x], sy[y], sx[x+1], sy[y+1]) to RectF(dx[x], dy[y], dx[x+1], dy[y+1]))
            }
        }
    }
    val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    onDrawBehind {
        drawIntoCanvas { target ->
            for ((source, destination) in slices) target.nativeCanvas.drawBitmap(bitmap, source, destination, bitmapPaint)
        }
    }
}

internal fun Modifier.pebbleFocus(focused: Boolean, radius: Dp = 16.dp): Modifier =
    if (!focused) this else drawWithCache {
        val stroke = 2.dp.toPx()
        val offset = 3.dp.toPx() + stroke / 2
        val corner = CornerRadius(radius.toPx() + offset)
        val ring = Size(size.width + offset * 2, size.height + offset * 2)
        onDrawBehind {
            drawRoundRect(PebbleVisuals.Focus, Offset(-offset, -offset), ring, corner, style = Stroke(stroke))
        }
    }
