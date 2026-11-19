package com.mywatt.app.debug

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mywatt.app.data.RideLog
import com.mywatt.app.data.history.HistoryDatabase
import com.mywatt.app.engine.Library
import com.mywatt.app.engine.Ramp
import com.mywatt.app.engine.RecoverySetting
import com.mywatt.app.engine.Rep
import com.mywatt.app.engine.SessionController
import com.mywatt.app.engine.SessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-build check of the history and ride-log plumbing on a real phone, without riding.
 * Started with `adb shell am start -n com.mywatt.app/.MainActivity --es selftest history`.
 *
 * Rides every library session with synthetic data, saves the results into [DB_FILE] (never
 * the rider's own history) and reads them back; tags a scratch ride log in the cache. The
 * outcome goes to logcat under [TAG] and a toast. Pull [DB_FILE] with run-as to inspect it.
 */
object SelfTest {
    const val EXTRA = "selftest"
    const val DB_FILE = "mywatt-selftest.db"
    private const val TAG = "MyWattSelfTest"

    fun run(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val report = runCatching {
                val results = withContext(Dispatchers.Default) { rideLibrary() }
                withContext(Dispatchers.IO) {
                    activity.deleteDatabase(DB_FILE)
                    val db = HistoryDatabase.open(activity, DB_FILE)
                    try {
                        val start = System.currentTimeMillis()
                        results.forEachIndexed { i, r -> db.dao().insert(r, start + i * 1000L, start + i * 1000L + 500) }
                        val sessions = db.dao().sessions()
                        var mismatches = 0
                        sessions.forEach { record ->
                            val back = db.dao().sessionWithReps(record.id)!!
                            val original = results.first { it.sessionId == record.sessionId }
                            if (back.repResults != original.reps || record.verdict != original.verdict) {
                                mismatches++
                                Log.w(TAG, "Mismatch for ${record.sessionId}")
                            }
                        }
                        val reps = sessions.sumOf { db.dao().reps(it.id).size }
                        "history: ${sessions.size} sessions, $reps reps saved, $mismatches mismatches; " +
                            tagCheck(activity.cacheDir)
                    } finally {
                        db.close()
                    }
                }
            }.getOrElse { e ->
                Log.e(TAG, "Self-test crashed", e)
                "FAILED: $e"
            }
            Log.i(TAG, report)
            Toast.makeText(activity, "Self-test: $report", Toast.LENGTH_LONG).show()
        }
    }

    /** Every library session at FTP 200, ridden on target with HR rising in efforts and falling between. */
    private fun rideLibrary(): List<SessionResult> = Library.sessions.map { lib ->
        val controller = SessionController(lib.build(200, RecoverySetting.Building))
        var hr = 100
        var guard = 0
        while (controller.result.value == null) {
            val state = controller.state.value
            val block = state.block
            val target = state.targetWatts ?: state.easyTargetWatts ?: 100
            // The ramp is held until 280 W, which this rider cannot manage.
            val watts = if (block is Ramp && target >= 280) target - 30 else target
            hr = if (block is Rep || block is Ramp) minOf(hr + 1, 180) else maxOf(hr - 1, 100)
            controller.tick(watts, hr, 12, watts, 90)
            check(++guard < 20_000) { "${lib.id} never finished" }
        }
        val result = controller.result.value!!
        Log.i(TAG, "${lib.id}: ${result.reps.size} reps · ${result.verdict}")
        result
    }

    /** Opens a scratch log, tags it with a session id and checks the rename kept every row. */
    private fun tagCheck(dir: File): String {
        val file = File(dir, "ride_selftest.csv").apply { delete() }
        File(dir, "ride_selftest_vo2-5x5.csv").delete()
        val log = RideLog.open(file)
        log.append(0, 100, 100, 80, 7, true, 110, emptyList(), null, null)
        log.tagWithSession("vo2-5x5")
        log.append(1, 300, 280, 90, 12, true, 150, emptyList(), null, null, "rep1", 216, "green")
        log.close()
        val lines = log.file.readLines()
        val ok = log.file.name == "ride_selftest_vo2-5x5.csv" && lines.size == 3 && !file.exists()
        log.file.delete()
        return "csv tag ${if (ok) "ok" else "FAILED"} (${log.file.name}, ${lines.size} lines)"
    }
}
