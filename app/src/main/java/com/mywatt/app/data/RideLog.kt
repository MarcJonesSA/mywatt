package com.mywatt.app.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One CSV file per connection in app-private storage, one row per second while connected.
 *
 * Files live in `filesDir/ride_logs/` and are named by the wall-clock time the connection
 * came up. Missing values are written as empty cells so the column count never changes.
 *
 * TODO: nothing prunes old logs yet; at ~30 bytes a row that is roughly 100 kB an hour.
 */
class RideLog private constructor(val file: File) {

    private val writer = FileWriter(file, /* append = */ true)

    init {
        writer.write(HEADER)
        writer.write("\n")
        writer.flush()
    }

    /** Appends one row and flushes it, so a crash loses at most the current second. */
    fun append(
        elapsedSeconds: Long,
        rawWatts: Int?,
        smoothedWatts: Int?,
        cadenceRpm: Int?,
        gear: Int?,
        gearConfirmed: Boolean,
        heartRateBpm: Int?,
        rrIntervalsMs: List<Int>,
        hrr60: Int?,
        hrr120: Int?,
        block: String? = null,
        targetWatts: Int? = null,
        band: String? = null,
    ) {
        writer.write(
            formatRow(
                elapsedSeconds, rawWatts, smoothedWatts, cadenceRpm, gear, gearConfirmed,
                heartRateBpm, rrIntervalsMs, hrr60, hrr120, block, targetWatts, band,
            )
        )
        writer.write("\n")
        writer.flush()
    }

    fun close() {
        runCatching { writer.close() }
    }

    companion object {
        /**
         * gear_confirmed is 1 once the bike itself has reported the gear this connection, else 0.
         * rr_ms holds every RR interval received since the previous row, in ms, space separated.
         * hrr60 and hrr120 are blank except on the row where that recovery reading completes.
         * block, target_w and band describe the training session in progress (warmup, rep1,
         * rec1, ..., cooldown; the rep's target; green/yellow/orange/red against it during a
         * rep) and are blank outside a session.
         */
        const val HEADER =
            "elapsed_s,raw_watts,smoothed_watts,cadence_rpm,gear,gear_confirmed,hr_bpm,rr_ms,hrr60,hrr120," +
                "block,target_w,band"

        private const val DIRECTORY = "ride_logs"

        /** Starts a fresh log for a connection that has just come up. */
        fun start(context: Context, now: Date = Date()): RideLog {
            val dir = directory(context).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            var file = File(dir, "ride_$stamp.csv")
            var suffix = 1
            while (file.exists()) file = File(dir, "ride_${stamp}_${suffix++}.csv")
            return RideLog(file)
        }

        /** The most recently written log, or null if nothing has been recorded yet. */
        fun latestFile(context: Context): File? =
            directory(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") }
                ?.maxByOrNull { it.lastModified() }

        /** Pure formatter, kept separate so the CSV shape is pinned by a unit test. */
        fun formatRow(
            elapsedSeconds: Long,
            rawWatts: Int?,
            smoothedWatts: Int?,
            cadenceRpm: Int?,
            gear: Int?,
            gearConfirmed: Boolean,
            heartRateBpm: Int?,
            rrIntervalsMs: List<Int>,
            hrr60: Int?,
            hrr120: Int?,
            block: String? = null,
            targetWatts: Int? = null,
            band: String? = null,
        ): String = listOf(
            elapsedSeconds.toString(),
            rawWatts?.toString() ?: "",
            smoothedWatts?.toString() ?: "",
            cadenceRpm?.toString() ?: "",
            gear?.toString() ?: "",
            if (gearConfirmed) "1" else "0",
            heartRateBpm?.toString() ?: "",
            rrIntervalsMs.joinToString(" "),
            hrr60?.toString() ?: "",
            hrr120?.toString() ?: "",
            block ?: "",
            targetWatts?.toString() ?: "",
            band ?: "",
        ).joinToString(",")

        private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)
    }
}
