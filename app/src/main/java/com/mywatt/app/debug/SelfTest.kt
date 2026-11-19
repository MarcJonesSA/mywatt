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
                            tagCheck(activity.cacheDir) + "; " + migrationCheck(activity)
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

    /**
     * Writes a version-1 database exactly as Room 1 left it (schema, identity hash, one
     * session with two reps), opens it with the current Room, and checks the migration kept
     * every row and now accepts a rep with no power.
     */
    private suspend fun migrationCheck(context: android.content.Context): String {
        val name = "mywatt-migrate-selftest.db"
        context.deleteDatabase(name)
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        listOf(
            "CREATE TABLE `session_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, `sessionName` TEXT NOT NULL, `ftpWatts` INTEGER, `recoverySetting` TEXT NOT NULL, `completed` INTEGER NOT NULL, `verdict` TEXT NOT NULL, `ftpEstimate` INTEGER)",
            "CREATE TABLE `rep_results` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionRecordId` INTEGER NOT NULL, `repNumber` INTEGER NOT NULL, `targetWatts` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, `averageWatts` INTEGER NOT NULL, `gear` INTEGER, `preHr` INTEGER, `peakHr` INTEGER, `gateHr` INTEGER, `hrAt60` INTEGER, `hrr60` INTEGER, `minuteDrops` TEXT NOT NULL, `recoverySeconds` INTEGER, `gateTimedOut` INTEGER NOT NULL, FOREIGN KEY(`sessionRecordId`) REFERENCES `session_results`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX `index_rep_results_sessionRecordId` ON `rep_results` (`sessionRecordId`)",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT INTO room_master_table (id,identity_hash) VALUES(42, '8566bb952d2f4f966e34e7377c1313d1')",
            "INSERT INTO session_results VALUES (1, 1000, 2000, 'vo2-5x5', 'VO2 5x5', 200, 'Building', 1, 'v1 verdict', NULL)",
            "INSERT INTO rep_results VALUES (1, 1, 1, 216, 300, 214, 12, 120, 175, 147, 140, 35, '35 9', 80, 0)",
            "INSERT INTO rep_results VALUES (2, 1, 2, 216, 300, 220, 13, 125, 178, 151, 150, 28, '28', 95, 1)",
        ).forEach { raw.execSQL(it) }
        raw.version = 1
        raw.close()

        val db = HistoryDatabase.open(context, name)
        return try {
            val back = db.dao().sessionWithReps(1)!!
            val keptOk = back.session.verdict == "v1 verdict" &&
                back.repResults.map { it.averageWatts } == listOf(214, 220) &&
                back.repResults.map { it.minuteDrops } == listOf(listOf(35, 9), listOf(28)) &&
                back.repResults[1].gateTimedOut
            val noPower = SessionResult("x", listOf(back.repResults[0].copy(averageWatts = null)), true)
            val id = db.dao().insert(noPower, 3000, 4000)
            val nullOk = db.dao().sessionWithReps(id)!!.repResults.single().averageWatts == null
            "migration v1->v2 ${if (keptOk && nullOk) "ok" else "FAILED (kept=$keptOk null=$nullOk)"}"
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
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
