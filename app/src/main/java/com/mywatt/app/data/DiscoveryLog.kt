package com.mywatt.app.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic dump of everything the trainer exposes over GATT, one file per connection in
 * `filesDir/ride_logs/discovery_<timestamp>.txt`, next to the ride CSVs.
 *
 * The file starts with the service/characteristic table, then grows by one line for every
 * notification that is not Indoor Bike Data, so the gear number can be hunted down in a
 * characteristic we do not yet decode. Lines are flushed as written.
 *
 * Writers may be called from Bluetooth binder threads, hence the synchronisation.
 */
class DiscoveryLog private constructor(val file: File) {

    private val writer = FileWriter(file, /* append = */ true)

    @Synchronized
    fun line(text: String) {
        runCatching {
            writer.write(text)
            writer.write("\n")
            writer.flush()
        }
    }

    /** Appends a notification from a characteristic we do not decode. */
    fun notification(elapsedSeconds: Long, characteristicUuid: String, payload: ByteArray) {
        line("$elapsedSeconds,$characteristicUuid,${toHex(payload)}")
    }

    @Synchronized
    fun close() {
        runCatching { writer.close() }
    }

    companion object {
        private const val DIRECTORY = "ride_logs"
        private const val PREFIX = "discovery_"

        fun start(context: Context, now: Date = Date()): DiscoveryLog {
            val dir = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
            var file = File(dir, "$PREFIX$stamp.txt")
            var suffix = 1
            while (file.exists()) file = File(dir, "$PREFIX${stamp}_${suffix++}.txt")
            return DiscoveryLog(file)
        }

        /** The most recently written discovery file, or null if none exists yet. */
        fun latestFile(context: Context): File? =
            File(context.filesDir, DIRECTORY)
                .listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".txt") }
                ?.maxByOrNull { it.lastModified() }

        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
    }
}
