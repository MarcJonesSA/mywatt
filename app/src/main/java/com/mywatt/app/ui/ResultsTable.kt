package com.mywatt.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.mywatt.app.R
import com.mywatt.app.engine.Band
import com.mywatt.app.engine.RepResult

/** The per-rep results table, shared by the session results page and the history. */
object ResultsTable {

    /** Fills [table] with a header row and one row per rep. */
    fun render(table: TableLayout, reps: List<RepResult>) {
        val context = table.context
        table.removeAllViews()
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val none = context.getString(R.string.no_reading)
        table.addView(
            row(
                context,
                listOf(
                    R.string.results_rep, R.string.results_avg, R.string.results_target, R.string.results_band,
                    R.string.results_peak, R.string.results_recovery, R.string.results_timed_out,
                ).map { cell(context, context.getString(it), secondary, bold = false) },
            )
        )
        reps.forEach { rep ->
            table.addView(
                row(
                    context,
                    listOf(
                        cell(context, rep.repNumber.toString(), primary, bold = true),
                        cell(context, rep.averageWatts.toString(), primary, bold = true),
                        cell(context, rep.targetWatts.toString(), primary, bold = false),
                        cell(context, rep.band.label, bandColor(context, rep.band), bold = true),
                        cell(context, rep.peakHr?.toString() ?: none, primary, bold = false),
                        cell(context, rep.recoverySeconds?.toString() ?: none, primary, bold = false),
                        cell(
                            context,
                            if (rep.gateTimedOut) context.getString(R.string.results_yes) else "",
                            primary,
                            bold = false,
                        ),
                    ),
                )
            )
        }
    }

    @ColorInt
    fun bandColor(context: Context, band: Band): Int = ContextCompat.getColor(
        context,
        when (band) {
            Band.GREEN -> R.color.zone_2
            Band.YELLOW -> R.color.zone_4
            Band.ORANGE -> R.color.zone_5
            Band.RED -> R.color.zone_6
        },
    )

    private fun row(context: Context, cells: List<TextView>): TableRow = TableRow(context).also { row ->
        row.layoutParams = TableLayout.LayoutParams(
            TableLayout.LayoutParams.WRAP_CONTENT, TableLayout.LayoutParams.WRAP_CONTENT,
        )
        cells.forEach { row.addView(it) }
    }

    private fun cell(context: Context, text: String, @ColorInt colour: Int, bold: Boolean): TextView =
        TextView(context).also {
            it.text = text
            it.setTextColor(colour)
            it.textSize = 15f
            it.gravity = Gravity.END
            it.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            val pad = (context.resources.displayMetrics.density * 6).toInt()
            it.setPadding(pad * 2, pad, pad * 2, pad)
        }
}
