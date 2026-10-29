package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RideLogTest {

    @Test
    fun `writes every column in header order`() {
        assertEquals(
            "elapsed_s,raw_watts,smoothed_watts,cadence_rpm,gear,gear_confirmed,hr_bpm,rr_ms",
            RideLog.HEADER,
        )
        assertEquals(
            "12,251,248,91,14,1,142,410 425",
            RideLog.formatRow(12, 251, 248, 91, 14, true, 142, listOf(410, 425)),
        )
    }

    @Test
    fun `leaves missing values as empty cells`() {
        assertEquals("0,,,,,0,,", RideLog.formatRow(0, null, null, null, null, false, null, emptyList()))
        assertEquals("5,200,,88,,0,,", RideLog.formatRow(5, 200, null, 88, null, false, null, emptyList()))
    }

    @Test
    fun `marks a remembered gear as unconfirmed`() {
        assertEquals("3,180,182,85,9,0,,", RideLog.formatRow(3, 180, 182, 85, 9, false, null, emptyList()))
    }

    @Test
    fun `writes heart rate without RR intervals as an empty rr cell`() {
        assertEquals("7,210,205,90,10,1,131,", RideLog.formatRow(7, 210, 205, 90, 10, true, 131, emptyList()))
    }
}
