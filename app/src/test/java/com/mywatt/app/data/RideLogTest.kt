package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RideLogTest {

    @Test
    fun `writes every column in header order`() {
        assertEquals(
            "elapsed_s,raw_watts,smoothed_watts,cadence_rpm,gear,gear_confirmed",
            RideLog.HEADER,
        )
        assertEquals("12,251,248,91,14,1", RideLog.formatRow(12, 251, 248, 91, 14, true))
    }

    @Test
    fun `leaves missing values as empty cells`() {
        assertEquals("0,,,,,0", RideLog.formatRow(0, null, null, null, null, false))
        assertEquals("5,200,,88,,0", RideLog.formatRow(5, 200, null, 88, null, false))
    }

    @Test
    fun `marks a remembered gear as unconfirmed`() {
        assertEquals("3,180,182,85,9,0", RideLog.formatRow(3, 180, 182, 85, 9, false))
    }
}
