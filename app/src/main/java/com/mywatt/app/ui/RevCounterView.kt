package com.mywatt.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.mywatt.app.R
import com.mywatt.app.data.PowerZone
import com.mywatt.app.data.PowerZones
import com.mywatt.app.data.RiderSettings
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Semicircular rev counter for power output.
 *
 * The dial sweeps 180 degrees from nine o'clock to three o'clock and is banded with the
 * six training zones, all derived from FTP. The band ahead of the needle is dimmed and the
 * band behind it is lit, so the arc reads like a rev counter sweeping up through the range.
 */
class RevCounterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var ftp: Int = RiderSettings.DEFAULT_FTP
    private var maxWatts: Int = PowerZones.gaugeMaxWatts(ftp)
    private var spans: List<PowerZones.ZoneSpan> = PowerZones.gaugeSpans(ftp)

    /** Zone colours resolved once and indexed by ordinal: onDraw runs on every animation frame. */
    private val zoneColours: IntArray = IntArray(PowerZone.entries.size) { index ->
        ContextCompat.getColor(context, PowerZone.entries[index].colorRes)
    }
    private val dimZoneColours: IntArray = IntArray(zoneColours.size) { index ->
        withAlpha(zoneColours[index], DIM_ALPHA)
    }
    private val wattsLabel: String = context.getString(R.string.unit_watts)

    /** The wattage the needle is pointing at right now, animated towards the latest reading. */
    private var displayedWatts: Float = 0f

    /** The latest reading, or null when there is no live data to show. */
    private var currentWatts: Int? = null

    private var needleAnimator: ValueAnimator? = null

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.gauge_track)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.gauge_tick)
    }
    private val ftpMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.needle)
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.needle)
    }
    private val hubInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.background)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val arcRect = RectF()
    private val needlePath = Path()

    /** Updates FTP, which rescales the dial and every zone band. */
    fun setFtp(newFtp: Int) {
        if (newFtp == ftp) return
        ftp = newFtp
        maxWatts = PowerZones.gaugeMaxWatts(ftp)
        spans = PowerZones.gaugeSpans(ftp)
        invalidate()
    }

    /**
     * Points the needle at [watts], sweeping smoothly from where it currently sits.
     * Pass null when there is no live reading: the needle falls back to zero and the
     * readout blanks out.
     */
    fun setPower(watts: Int?) {
        currentWatts = watts
        animateNeedleTo((watts ?: 0).toFloat().coerceIn(0f, maxWatts.toFloat()))
    }

    private fun animateNeedleTo(target: Float) {
        needleAnimator?.cancel()
        needleAnimator = ValueAnimator.ofFloat(displayedWatts, target).apply {
            duration = NEEDLE_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedWatts = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        needleAnimator?.cancel()
        needleAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // A semicircle plus the readout block that sits under the hub.
        val desiredHeight = (width * HEIGHT_TO_WIDTH_RATIO).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom
        if (usableWidth <= 0 || usableHeight <= 0) return

        val radius = min(usableWidth / 2f, usableHeight / HEIGHT_TO_WIDTH_RATIO / 2f)
        val centreX = paddingLeft + usableWidth / 2f
        val centreY = paddingTop + radius

        val bandWidth = radius * BAND_WIDTH_RATIO
        val bandRadius = radius - bandWidth / 2f
        arcRect.set(
            centreX - bandRadius,
            centreY - bandRadius,
            centreX + bandRadius,
            centreY + bandRadius,
        )

        drawZoneBands(canvas, bandWidth)
        drawTicks(canvas, centreX, centreY, bandRadius, bandWidth)
        drawNeedle(canvas, centreX, centreY, bandRadius - bandWidth / 2f)
        drawReadout(canvas, centreX, centreY, radius)
    }

    private fun drawZoneBands(canvas: Canvas, bandWidth: Float) {
        bandPaint.strokeWidth = bandWidth
        trackPaint.strokeWidth = bandWidth

        // Unreached part of the dial first, dimmed; then the lit part up to the needle.
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)
        for (span in spans) {
            val colour = zoneColours[span.zone.ordinal]
            val startAngle = angleFor(span.startWatts.toFloat())
            val sweep = angleFor(span.endWatts.toFloat()) - startAngle
            if (sweep <= 0f) continue

            bandPaint.color = dimZoneColours[span.zone.ordinal]
            canvas.drawArc(arcRect, startAngle, sweep, false, bandPaint)

            // Lit portion: whatever of this zone the needle has already swept past.
            val litEnd = min(displayedWatts, span.endWatts.toFloat())
            if (litEnd > span.startWatts) {
                val litSweep = angleFor(litEnd) - startAngle
                if (litSweep > 0f) {
                    bandPaint.color = colour
                    canvas.drawArc(arcRect, startAngle, litSweep, false, bandPaint)
                }
            }
        }
    }

    private fun drawTicks(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        bandRadius: Float,
        bandWidth: Float,
    ) {
        val step = tickStepWatts()
        val outerRadius = bandRadius - bandWidth / 2f
        val innerRadius = outerRadius - bandWidth * 0.45f
        tickPaint.strokeWidth = max(2f, bandWidth * 0.07f)
        tickLabelPaint.textSize = bandWidth * 0.62f

        var watts = 0
        while (watts <= maxWatts) {
            val angle = Math.toRadians(angleFor(watts.toFloat()).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                centreX + cosA * outerRadius,
                centreY + sinA * outerRadius,
                centreX + cosA * innerRadius,
                centreY + sinA * innerRadius,
                tickPaint,
            )

            val labelRadius = innerRadius - tickLabelPaint.textSize * 0.85f
            canvas.drawText(
                watts.toString(),
                centreX + cosA * labelRadius,
                centreY + sinA * labelRadius + tickLabelPaint.textSize * 0.35f,
                tickLabelPaint,
            )
            watts += step
        }

        // Threshold marker: the one wattage the rider cares about most.
        if (ftp in 1..maxWatts) {
            val angle = Math.toRadians(angleFor(ftp.toFloat()).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            ftpMarkerPaint.strokeWidth = max(3f, bandWidth * 0.12f)
            canvas.drawLine(
                centreX + cosA * (bandRadius + bandWidth / 2f),
                centreY + sinA * (bandRadius + bandWidth / 2f),
                centreX + cosA * (bandRadius - bandWidth / 2f),
                centreY + sinA * (bandRadius - bandWidth / 2f),
                ftpMarkerPaint,
            )
        }
    }

    private fun drawNeedle(canvas: Canvas, centreX: Float, centreY: Float, length: Float) {
        val angle = Math.toRadians(angleFor(displayedWatts).toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        // Perpendicular to the needle, used to give it a tapered base.
        val baseHalfWidth = length * 0.035f
        val perpX = -sinA * baseHalfWidth
        val perpY = cosA * baseHalfWidth
        val tailLength = length * 0.12f

        needlePath.reset()
        needlePath.moveTo(centreX + cosA * length, centreY + sinA * length)
        needlePath.lineTo(centreX + perpX, centreY + perpY)
        needlePath.lineTo(centreX - cosA * tailLength, centreY - sinA * tailLength)
        needlePath.lineTo(centreX - perpX, centreY - perpY)
        needlePath.close()

        needlePaint.color = zoneColours[currentZone().ordinal]
        canvas.drawPath(needlePath, needlePaint)

        val hubRadius = length * 0.075f
        canvas.drawCircle(centreX, centreY, hubRadius, hubPaint)
        canvas.drawCircle(centreX, centreY, hubRadius * 0.5f, hubInnerPaint)
    }

    private fun drawReadout(canvas: Canvas, centreX: Float, centreY: Float, radius: Float) {
        valuePaint.textSize = radius * 0.40f
        unitPaint.textSize = radius * 0.13f

        val valueText = currentWatts?.toString() ?: NO_READING
        val valueBaseline = centreY + radius * 0.34f
        canvas.drawText(valueText, centreX, valueBaseline, valuePaint)
        canvas.drawText(
            wattsLabel,
            centreX,
            valueBaseline + unitPaint.textSize * 1.5f,
            unitPaint,
        )
    }

    private fun currentZone(): PowerZone = PowerZones.zoneFor(currentWatts ?: 0, ftp)

    /** Maps a wattage onto its angle on the dial, in canvas degrees. */
    private fun angleFor(watts: Float): Float {
        if (maxWatts <= 0) return START_ANGLE
        val fraction = (watts / maxWatts).coerceIn(0f, 1f)
        return START_ANGLE + fraction * SWEEP_ANGLE
    }

    /** Picks a tidy tick interval that keeps the dial to roughly eight labelled marks. */
    private fun tickStepWatts(): Int {
        val candidates = intArrayOf(25, 50, 100, 150, 200, 250, 500)
        return candidates.firstOrNull { maxWatts / it <= MAX_TICKS } ?: 500
    }

    private fun withAlpha(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

    companion object {
        /** Nine o'clock on the canvas; angles grow clockwise from three o'clock. */
        private const val START_ANGLE = 180f
        private const val SWEEP_ANGLE = 180f

        private const val BAND_WIDTH_RATIO = 0.15f
        private const val HEIGHT_TO_WIDTH_RATIO = 0.80f
        private const val DIM_ALPHA = 60
        private const val MAX_TICKS = 8
        private const val NEEDLE_ANIMATION_MS = 350L
        private const val NO_READING = "--"
    }
}
