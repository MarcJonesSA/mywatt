package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryProfileTest {

    @Test
    fun `riding now splits on intervals before`() {
        WeeklyHours.entries.forEach { _ ->
            assertEquals(EntryProfile.TRAINED, EntryProfile.classify(true, null, intervalsBefore = true))
            assertEquals(EntryProfile.PLATEAU, EntryProfile.classify(true, null, intervalsBefore = false))
        }
    }

    @Test
    fun `not riding splits on how long since`() {
        listOf(true, false).forEach { intervals ->
            assertEquals(EntryProfile.SHORT_BREAK, EntryProfile.classify(false, TimeOff.UNDER_2_MONTHS, intervals))
            assertEquals(EntryProfile.LONG_BREAK, EntryProfile.classify(false, TimeOff.MONTHS_2_TO_12, intervals))
            assertEquals(EntryProfile.LONG_BREAK, EntryProfile.classify(false, TimeOff.OVER_A_YEAR, intervals))
            assertEquals(EntryProfile.BEGINNER, EntryProfile.classify(false, TimeOff.NEVER, intervals))
        }
    }

    @Test
    fun `names round-trip and unknown names are null`() {
        EntryProfile.entries.forEach { assertEquals(it, EntryProfile.fromName(it.name)) }
        assertEquals(null, EntryProfile.fromName("NOPE"))
    }
}
