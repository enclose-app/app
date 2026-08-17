package io.app.enclose.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/**
 * The numbered badge dropped on the trail at each kilometre.
 *
 * Drawn in code, like [homeMarkerBitmap], but here the reason is stronger than
 * theming: **the app has to work with no network**, and a `text-field` on a
 * symbol layer is rendered from glyphs the basemap serves over HTTP. A walk in a
 * valley with no signal would get dots with no numbers on them — which is the
 * walk these markers are most use on. A bitmap carries its own number.
 *
 * The badge is left at the display's own density for the reason [homeMarkerBitmap]
 * records: MapLibre reads [Bitmap.getDensity] for the icon's pixel ratio, so this
 * lands at [SIZE_DP] on screen at `iconSize(1f)` on every device.
 */
internal fun milestoneMarkerBitmap(
    context: Context,
    label: String,
    fillColor: Int,
    textColor: Int,
): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = SIZE_DP * density
    val stroke = STROKE_DP * density

    val bitmap = createBitmap(size.toInt(), size.toInt())
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = center - stroke / 2f

    canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
    // The same white ring the start dot and the home pin wear: it is what keeps a
    // marker legible over both pale pavement and dark parkland, and here it also
    // separates the badge from the trail line running underneath it.
    canvas.drawCircle(
        center,
        center,
        radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = stroke
        },
    )

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = TEXT_DP * density
    }
    // Shrink rather than overflow: "12" is the common case and fits, but the cap
    // on markers allows three digits, and a badge with the label spilling past
    // its ring reads as a rendering fault rather than as a distance.
    val usable = (radius - stroke) * 2f
    val measured = paint.measureText(label)
    if (measured > usable) paint.textSize *= usable / measured
    // Centre on the glyphs' own vertical extent, not on the font's line box —
    // digits have no descender, so a baseline placed from the metrics alone sits
    // the number visibly low in the circle.
    val bounds = android.graphics.Rect()
    paint.getTextBounds(label, 0, label.length, bounds)
    canvas.drawText(label, center, center + bounds.height() / 2f, paint)

    return bitmap
}

private const val SIZE_DP = 22f
private const val STROKE_DP = 2f
private const val TEXT_DP = 12f
