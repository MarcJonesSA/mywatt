package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RideLogTagTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the session id goes before the extension, once`() {
        assertEquals("ride_20261119_101500_vo2-5x5.csv", RideLog.taggedName("ride_20261119_101500.csv", "vo2-5x5"))
        assertEquals("ride_20261119_101500_1_tabata.csv", RideLog.taggedName("ride_20261119_101500_1.csv", "tabata"))
        // Already there, blank, or not file-safe: unchanged.
        assertEquals("ride_1_tabata.csv", RideLog.taggedName("ride_1_tabata.csv", "tabata"))
        assertEquals("ride_1.csv", RideLog.taggedName("ride_1.csv", ""))
        assertEquals("ride_1.csv", RideLog.taggedName("ride_1.csv", "../x"))
        // A second session in the same connection adds its id too.
        assertEquals("ride_1_tabata_z2-45.csv", RideLog.taggedName("ride_1_tabata.csv", "z2-45"))
    }

    @Test
    fun `tagging renames the open log and keeps writing to it`() {
        val original = File(temp.root, "ride_20261119_101500.csv")
        val log = RideLog.open(original)
        log.append(0, 100, 100, 80, 7, true, 110, emptyList(), null, null)
        log.tagWithSession("vo2-5x5")
        log.append(1, 300, 280, 95, 12, true, 150, emptyList(), null, null, "rep1", 270, "green")
        log.tagWithSession("vo2-5x5")
        log.close()

        assertFalse(original.exists())
        val tagged = File(temp.root, "ride_20261119_101500_vo2-5x5.csv")
        assertEquals(tagged, log.file)
        assertTrue(tagged.exists())
        assertEquals(
            listOf(
                RideLog.HEADER,
                "0,100,100,80,7,1,110,,,,,,",
                "1,300,280,95,12,1,150,,,,rep1,270,green",
            ),
            tagged.readLines(),
        )
    }
}
