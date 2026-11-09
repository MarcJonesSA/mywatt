package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RideLogTest {

    @Test
    fun `writes every column in header order`() {
        assertEquals(
            "elapsed_s,raw_watts,smoothed_watts,cadence_rpm,gear,gear_confirmed,hr_bpm,rr_ms,hrr60,hrr120," +
                "block,target_w,band",
            RideLog.HEADER,
        )
        assertEquals(
            "12,251,248,91,14,1,142,410 425,13,,rep1,250,green",
            RideLog.formatRow(12, 251, 248, 91, 14, true, 142, listOf(410, 425), 13, null, "rep1", 250, "green"),
        )
    }

    @Test
    fun `leaves missing values as empty cells`() {
        assertEquals(
            "0,,,,,0,,,,,,,",
            RideLog.formatRow(0, null, null, null, null, false, null, emptyList(), null, null),
        )
        assertEquals(
            "5,200,,88,,0,,,,,,,",
            RideLog.formatRow(5, 200, null, 88, null, false, null, emptyList(), null, null),
        )
    }

    @Test
    fun `marks a remembered gear as unconfirmed`() {
        assertEquals(
            "3,180,182,85,9,0,,,,,,,",
            RideLog.formatRow(3, 180, 182, 85, 9, false, null, emptyList(), null, null),
        )
    }

    @Test
    fun `writes heart rate without RR intervals as an empty rr cell`() {
        assertEquals(
            "7,210,205,90,10,1,131,,,,,,",
            RideLog.formatRow(7, 210, 205, 90, 10, true, 131, emptyList(), null, null),
        )
    }

    @Test
    fun `carries hrr60 and hrr120 only on their completing rows`() {
        assertEquals(
            "194,85,85,80,7,1,70,,92,,,,",
            RideLog.formatRow(194, 85, 85, 80, 7, true, 70, emptyList(), 92, null),
        )
        assertEquals(
            "254,85,85,80,7,1,70,,,92,,,",
            RideLog.formatRow(254, 85, 85, 80, 7, true, 70, emptyList(), null, 92),
        )
    }

    @Test
    fun `session columns name the block and are blank for target and band outside a rep`() {
        assertEquals(
            "400,90,90,80,7,1,155,,,,rec1,,",
            RideLog.formatRow(400, 90, 90, 80, 7, true, 155, emptyList(), null, null, "rec1", null, null),
        )
        assertEquals(
            "100,100,100,80,7,1,110,,,,warmup,,",
            RideLog.formatRow(100, 100, 100, 80, 7, true, 110, emptyList(), null, null, "warmup", null, null),
        )
    }
}
