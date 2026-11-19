package com.mywatt.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.mywatt.app.R
import com.mywatt.app.data.PowerZones
import com.mywatt.app.engine.ProfileBar
import com.mywatt.app.engine.Session
import com.mywatt.app.engine.SessionProfile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A bar profile of a session: one bar per block, width = time, height = target % FTP,
 * coloured by power zone. Gated recoveries are hatched (their length is unknown and they
 * are drawn at a nominal 2 min). With a position set, the current block is drawn bright,
 * the rest dimmed, and a marker shows where the rider is.
 */
class SessionProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bars: List<ProfileBar> = emptyList()
    private var ftp = 200
    private var total = 0
    private var currentBlock: Int? = null
    private var positionSeconds: Int? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val baseline = Paint().apply {
        color = ContextCompat.getColor(context, R.color.outline)
        strokeWidth = 1f * density
    }
    private val ftpLine = Paint().apply {
        color = ContextCompat.getColor(context, R.color.gauge_tick)
        strokeWidth = 1f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        strokeWidth = 2f * density
    }
    private val markerHead = Path()
    private val barPath = Path()

    fun setSession(session: Session) {
        bars = SessionProfile.bars(session)
        ftp = SessionProfile.ftpFor(session)
        total = SessionProfile.totalSeconds(bars)
        invalidate()
    }

    /** Highlights block [blockIndex] and puts the marker [secondsInBlock] into it; null clears both. */
    fun setPosition(blockIndex: Int?, secondsInBlock: Int = 0) {
        val position = blockIndex?.let { SessionProfile.positionSeconds(bars, it, secondsInBlock) }
        if (blockIndex == currentBlock && position == positionSeconds) return
        currentBlock = blockIndex
        positionSeconds = position
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = (DEFAULT_HEIGHT_DP * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty() || total <= 0) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val markerRoom = 6 * density
        val top = paddingTop + markerRoom
        val bottom = (height - paddingBottom).toFloat()
        val span = right - left
        val heightAvail = bottom - top
        if (span <= 0 || heightAvail <= 0) return
        val maxPercent = max(MIN_SCALE_PERCENT, bars.maxOf { max(it.startPercent, it.endPercent) } * 1.05)

        fun x(seconds: Int) = left + span * seconds / total
        fun y(percent: Double) = (bottom - heightAvail * (percent / maxPercent)).toFloat()

        val highlighting = currentBlock != null
        bars.forEach { bar ->
            val x0 = x(bar.startSeconds)
            val x1 = max(x(bar.endSeconds) - GAP_DP * density, x0 + 0.8f * density)
            val colour = zoneColour((bar.startPercent + bar.endPercent) / 2)
            val bright = !highlighting || bar.blockIndex == currentBlock
            val alpha = if (bright) 1f else DIM_ALPHA
            barPath.reset()
            barPath.moveTo(x0, bottom)
            barPath.lineTo(x0, y(bar.startPercent))
            barPath.lineTo(x1, y(bar.endPercent))
            barPath.lineTo(x1, bottom)
            barPath.close()
            if (bar.hatched) {
                fill.color = ColorUtils.setAlphaComponent(colour, (HATCH_FILL_ALPHA * alpha * 255).roundToInt())
                canvas.drawPath(barPath, fill)
                hatch.color = ColorUtils.setAlphaComponent(colour, (alpha * 255).roundToInt())
                canvas.save()
                canvas.clipPath(barPath)
                val step = 5 * density
                var hx = x0 - heightAvail
                while (hx < x1) {
                    canvas.drawLine(hx, bottom, hx + heightAvail, top, hatch)
                    hx += step
                }
                canvas.restore()
            } else {
                fill.color = ColorUtils.setAlphaComponent(colour, (alpha * 255).roundToInt())
                canvas.drawPath(barPath, fill)
            }
        }
        canvas.drawLine(left, y(100.0), right, y(100.0), ftpLine)
        canvas.drawLine(left, bottom, right, bottom, baseline)

        positionSeconds?.let { pos ->
            val mx = x(pos).coerceIn(left, right)
            canvas.drawLine(mx, top, mx, bottom, marker)
            markerHead.reset()
            markerHead.moveTo(mx - markerRoom, paddingTop.toFloat())
            markerHead.lineTo(mx + markerRoom, paddingTop.toFloat())
            markerHead.lineTo(mx, top)
            markerHead.close()
            canvas.drawPath(markerHead, marker)
        }
    }

    private fun zoneColour(percent: Double): Int =
        ContextCompat.getColor(context, PowerZones.zoneFor((percent * ftp / 100).roundToInt(), ftp).colorRes)

    companion object {
        private const val DEFAULT_HEIGHT_DP = 56
        private const val GAP_DP = 0.6f
        private const val DIM_ALPHA = 0.4f
        private const val HATCH_FILL_ALPHA = 0.25f
        private const val MIN_SCALE_PERCENT = 130.0
    }
}
