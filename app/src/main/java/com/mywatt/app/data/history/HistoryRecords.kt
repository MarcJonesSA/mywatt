package com.mywatt.app.data.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mywatt.app.engine.RecoverySetting
import com.mywatt.app.engine.RepResult
import com.mywatt.app.engine.SessionResult

/** One finished (or ended) training session, as kept in the history. */
@Entity(tableName = "session_results")
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock time the session was started, ms since the epoch. */
    val startedAtMs: Long,
    /** Wall-clock time the result was recorded. */
    val endedAtMs: Long,
    /** Library id, e.g. vo2-5x5; empty for a session not from the library. */
    val sessionId: String,
    val sessionName: String,
    /** FTP the targets were worked out from. */
    val ftpWatts: Int?,
    /** [RecoverySetting.name] the HR gates used. */
    val recoverySetting: String,
    val completed: Boolean,
    val verdict: String,
    /** FTP estimate, for a test that produced one. */
    val ftpEstimate: Int?,
)

/** One rep of a [SessionRecord]; mirrors [RepResult]. */
@Entity(
    tableName = "rep_results",
    foreignKeys = [
        ForeignKey(
            entity = SessionRecord::class,
            parentColumns = ["id"],
            childColumns = ["sessionRecordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionRecordId")],
)
data class RepRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionRecordId: Long,
    val repNumber: Int,
    val targetWatts: Int,
    val seconds: Int,
    /** Null when the rep had no power data (since schema version 2). */
    val averageWatts: Int?,
    val gear: Int?,
    val preHr: Int?,
    val peakHr: Int?,
    val gateHr: Int?,
    val hrAt60: Int?,
    val hrr60: Int?,
    /** Per-minute HR drops, space separated (e.g. "32 11"). */
    val minuteDrops: String,
    val recoverySeconds: Int?,
    val gateTimedOut: Boolean,
)

/** A session with its reps, as read back for the history screen. */
data class SessionWithReps(val session: SessionRecord, val reps: List<RepRecord>) {
    val recoverySetting: RecoverySetting get() = RecoverySetting.fromName(session.recoverySetting)
    val repResults: List<RepResult> get() = reps.sortedBy { it.repNumber }.map { it.toRepResult() }
}

/** The history row for [result], started at [startedAtMs] and recorded at [endedAtMs]. */
fun SessionResult.toRecord(startedAtMs: Long, endedAtMs: Long): SessionRecord = SessionRecord(
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    sessionId = sessionId,
    sessionName = sessionName,
    ftpWatts = ftpWatts,
    recoverySetting = recoverySetting.name,
    completed = completed,
    verdict = verdict,
    ftpEstimate = ftpEstimate,
)

fun RepResult.toRecord(sessionRecordId: Long): RepRecord = RepRecord(
    sessionRecordId = sessionRecordId,
    repNumber = repNumber,
    targetWatts = targetWatts,
    seconds = seconds,
    averageWatts = averageWatts,
    gear = gear,
    preHr = preHr,
    peakHr = peakHr,
    gateHr = gateHr,
    hrAt60 = hrAt60,
    hrr60 = hrr60,
    minuteDrops = minuteDrops.joinToString(" "),
    recoverySeconds = recoverySeconds,
    gateTimedOut = gateTimedOut,
)

fun RepRecord.toRepResult(): RepResult = RepResult(
    repNumber = repNumber,
    targetWatts = targetWatts,
    seconds = seconds,
    averageWatts = averageWatts,
    gear = gear,
    preHr = preHr,
    peakHr = peakHr,
    gateHr = gateHr,
    hrAt60 = hrAt60,
    hrr60 = hrr60,
    minuteDrops = minuteDrops.split(' ').mapNotNull { it.toIntOrNull() },
    recoverySeconds = recoverySeconds,
    gateTimedOut = gateTimedOut,
)
