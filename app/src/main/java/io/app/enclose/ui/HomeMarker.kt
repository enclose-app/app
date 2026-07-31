package io.app.enclose.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.app.enclose.R
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pin drawn on the map at the saved home position.
 *
 * Built in code rather than shipped as a drawable so the fill can come from the
 * theme's `home` accent — the same value the home *button* is tinted with, so
 * the control and the pin it flies to can't drift apart. The white outline
 * matches the walk's start dot ([EncloseMap]'s start layer), which is what keeps
 * a marker legible over both pale pavement and dark parkland.
 *
 * The bitmap is left at the display's own density: MapLibre reads
 * [Bitmap.getDensity] to work out the icon's pixel ratio, so a marker built this
 * way lands at [WIDTH_DP] × [HEIGHT_DP] on screen at `iconSize(1f)` on every
 * device.
 */
internal fun homeMarkerBitmap(context: Context, fillColor: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = WIDTH_DP * density
    val height = HEIGHT_DP * density
    val stroke = STROKE_DP * density

    val bitmap = createBitmap(width.toInt(), height.toInt())
    val canvas = Canvas(bitmap)

    // Head circle, inset by the stroke so the outline isn't clipped by the edge.
    val radius = width / 2f - stroke
    val cx = width / 2f
    val cy = stroke + radius
    val tipY = height - stroke

    val pin = teardrop(cx, cy, radius, tipY)

    canvas.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
    canvas.drawPath(
        pin,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke
        },
    )

    // The house sits inside the head, tinted white by the drawable itself.
    val glyph = ContextCompat.getDrawable(context, R.drawable.ic_home_glyph)
    if (glyph != null) {
        val half = radius * GLYPH_FRACTION
        glyph.setBounds(
            (cx - half).toInt(),
            (cy - half).toInt(),
            (cx + half).toInt(),
            (cy + half).toInt(),
        )
        glyph.draw(canvas)
    }
    return bitmap
}

/**
 * A map-pin silhouette: a circle at ([cx], [cy]) of [radius], drawn down to a
 * point at [tipY] along the two lines tangent to it.
 *
 * Tangent rather than a circle with a triangle stuck on it, because the outline
 * is stroked: overlapping shapes would draw their seams straight across the pin.
 */
private fun teardrop(cx: Float, cy: Float, radius: Float, tipY: Float): Path {
    val path = Path()
    val toTip = tipY - cy
    // Degenerate geometry (a tip inside the circle) has no tangents; a plain
    // circle is the sane thing to draw and can't crash.
    if (toTip <= radius) {
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        return path
    }
    // Angle at the centre between "straight down" and the tangent contact point.
    val phi = acos(radius / toTip)
    val right = Math.toDegrees(phi.toDouble()).let { 90.0 - it }.toFloat()
    val left = 180f - right
    val touchRight = (Math.PI / 180.0 * right).toFloat()
    val touchLeft = (Math.PI / 180.0 * left).toFloat()

    path.moveTo(cx + radius * cos(touchRight), cy + radius * sin(touchRight))
    path.lineTo(cx, tipY)
    path.lineTo(cx + radius * cos(touchLeft), cy + radius * sin(touchLeft))
    // From the left contact point round the top back to the right one.
    path.arcTo(
        RectF(cx - radius, cy - radius, cx + radius, cy + radius),
        left,
        360f - (left - right),
        false,
    )
    path.close()
    return path
}

private const val WIDTH_DP = 34f
private const val HEIGHT_DP = 46f
private const val STROKE_DP = 2f

/** How much of the head's radius the house fills. */
private const val GLYPH_FRACTION = 0.62f
