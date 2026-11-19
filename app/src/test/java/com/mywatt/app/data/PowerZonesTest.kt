package com.mywatt.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerZonesTest {

    private val ftp = 220

    @Test
    fun `puts wattages in the right zone at 220 W`() {
        assertEquals(PowerZone.Z1, PowerZones.zoneFor(0, ftp))
        assertEquals(PowerZone.Z1, PowerZones.zoneFor(120, ftp))
        assertEquals(PowerZone.Z2, PowerZones.zoneFor(121, ftp))
        assertEquals(PowerZone.Z2, PowerZones.zoneFor(164, ftp))
        assertEquals(PowerZone.Z3, PowerZones.zoneFor(165, ftp))
        assertEquals(PowerZone.Z4, PowerZones.zoneFor(198, ftp))
        assertEquals(PowerZone.Z4, PowerZones.zoneFor(ftp, ftp))
        assertEquals(PowerZone.Z5, PowerZones.zoneFor(231, ftp))
        assertEquals(PowerZone.Z6, PowerZones.zoneFor(264, ftp))
        assertEquals(PowerZone.Z6, PowerZones.zoneFor(900, ftp))
    }

    @Test
    fun `scales every boundary with FTP`() {
        assertEquals(PowerZone.Z2, PowerZones.zoneFor(200, 320))
        assertEquals(PowerZone.Z5, PowerZones.zoneFor(200, 180))
    }

    @Test
    fun `labels zone ranges in watts`() {
        assertEquals("121 - 164 W", PowerZone.Z2.rangeLabel(ftp))
        assertEquals("264+ W", PowerZone.Z6.rangeLabel(ftp))
    }

    @Test
    fun `full scale leaves headroom above threshold`() {
        assertEquals(450, PowerZones.gaugeMaxWatts(ftp))
        assertTrue(PowerZones.gaugeMaxWatts(ftp) > ftp)
        assertEquals(300, PowerZones.gaugeMaxWatts(50))
    }

    @Test
    fun `dial bands cover the whole sweep without gaps`() {
        val spans = PowerZones.gaugeSpans(ftp)

        assertEquals(PowerZone.entries.size, spans.size)
        assertEquals(0, spans.first().startWatts)
        assertEquals(PowerZones.gaugeMaxWatts(ftp), spans.last().endWatts)
        assertTrue(spans.zipWithNext().all { (a, b) -> a.endWatts == b.startWatts })
    }
}
