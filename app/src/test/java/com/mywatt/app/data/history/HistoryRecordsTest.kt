package com.mywatt.app.data.history

import com.mywatt.app.engine.FtpTest
import com.mywatt.app.engine.RecoverySetting
import com.mywatt.app.engine.RepResult
import com.mywatt.app.engine.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRecordsTest {

    private val rep1 = RepResult(1, 270, 300, 268, 14, 120, 178, 149, 140, 38, listOf(38, 9), 95, false)
    private val rep2 = RepResult(2, 270, 300, 255, null, null, null, null, null, null, emptyList(), null, true)

    private val result = SessionResult(
        sessionName = "VO2 5×5",
        reps = listOf(rep1, rep2),
        completed = false,
        recoverySetting = RecoverySetting.Trained,
        sessionId = "vo2-5x5",
        ftpWatts = 250,
    )

    @Test
    fun `a result becomes one session row`() {
        val record = result.toRecord(startedAtMs = 1_000, endedAtMs = 2_000)
        assertEquals(
            SessionRecord(
                id = 0,
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                sessionId = "vo2-5x5",
                sessionName = "VO2 5×5",
                ftpWatts = 250,
                recoverySetting = "Trained",
                completed = false,
                verdict = result.verdict,
                ftpEstimate = null,
            ),
            record,
        )
        assertEquals("1 of 2 reps on target · Recovery 95 s · Gate timed out on rep 2 · Ended early", record.verdict)
    }

    @Test
    fun `reps survive the round trip`() {
        val rows = result.reps.map { it.toRecord(sessionRecordId = 7) }
        assertEquals(listOf(7L, 7L), rows.map { it.sessionRecordId })
        assertEquals("38 9", rows[0].minuteDrops)
        assertEquals("", rows[1].minuteDrops)
        // Read back out of order: the history sorts by rep number.
        val read = SessionWithReps(result.toRecord(1, 2).copy(id = 7), rows.reversed())
        assertEquals(listOf(rep1, rep2), read.repResults)
        assertEquals(RecoverySetting.Trained, read.recoverySetting)
    }

    @Test
    fun `a test's estimate is stored`() {
        val ramp = SessionResult(
            "TEST Ramp",
            listOf(rep1.copy(targetWatts = 260)),
            completed = true,
            sessionId = "test-ramp",
            ftpWatts = 200,
            ftpTest = FtpTest.RAMP,
        )
        assertEquals(195, ramp.toRecord(0, 0).ftpEstimate)
    }
}
