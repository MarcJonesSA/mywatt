package com.mywatt.app.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FTMSParserTest {

    /** Builds an Indoor Bike Data payload the way a trainer would. */
    private class Packet {
        private val out = ArrayList<Int>()
        fun u8(value: Int) = apply { out.add(value and 0xFF) }
        fun u16(value: Int) = apply {
            out.add(value and 0xFF)
            out.add((value shr 8) and 0xFF)
        }

        fun u24(value: Int) = apply {
            out.add(value and 0xFF)
            out.add((value shr 8) and 0xFF)
            out.add((value shr 16) and 0xFF)
        }

        fun bytes(): ByteArray = ByteArray(out.size) { out[it].toByte() }
    }

    @Test
    fun `decodes speed cadence and power`() {
        // Flags 0x0044: More Data clear (speed present), instantaneous cadence, instantaneous power.
        val packet = Packet().u16(0x0044).u16(2500).u16(180).u16(250).bytes()

        val data = FTMSParser.parseIndoorBikeData(packet)!!

        assertEquals(25.0, data.instantaneousSpeedKph!!, 0.001)
        assertEquals(90.0, data.instantaneousCadenceRpm!!, 0.001)
        assertEquals(250, data.instantaneousPowerWatts)
    }

    @Test
    fun `omits speed when the more data bit is set`() {
        // The More Data bit is inverted: setting it means the speed field is absent, so power
        // must not be read out of the slot the speed would have occupied.
        val packet = Packet().u16(0x0045).u16(180).u16(250).bytes()

        val data = FTMSParser.parseIndoorBikeData(packet)!!

        assertNull(data.instantaneousSpeedKph)
        assertEquals(90.0, data.instantaneousCadenceRpm!!, 0.001)
        assertEquals(250, data.instantaneousPowerWatts)
    }

    @Test
    fun `reads power as a signed value`() {
        val packet = Packet().u16(0x0041).u16(0xFFFB).bytes()

        assertEquals(-5, FTMSParser.parseIndoorBikeData(packet)!!.instantaneousPowerWatts)
    }

    @Test
    fun `reads the resistance level as a signed value`() {
        // Flags 0x0061: More Data set (no speed), resistance level, instantaneous power.
        val packet = Packet().u16(0x0061).u16(12).u16(250).bytes()
        val data = FTMSParser.parseIndoorBikeData(packet)!!
        assertEquals(12, data.resistanceLevel)
        assertEquals(250, data.instantaneousPowerWatts)

        val negative = Packet().u16(0x0021).u16(0xFFFE).bytes()
        assertEquals(-2, FTMSParser.parseIndoorBikeData(negative)!!.resistanceLevel)
    }

    @Test
    fun `finds power in a packet carrying every optional field`() {
        val packet = Packet()
            .u16(0x1FFE)              // every flag set, More Data clear
            .u16(3000)                // speed, 30.00 km/h
            .u16(2800)                // average speed
            .u16(190)                 // cadence, 95 rpm
            .u16(184)                 // average cadence
            .u24(12345)               // total distance
            .u16(7)                   // resistance
            .u16(312)                 // instantaneous power
            .u16(280)                 // average power
            .u16(150).u16(600).u8(10) // expended energy
            .u8(142)                  // heart rate
            .u8(85)                   // metabolic equivalent
            .u16(1830)                // elapsed time
            .u16(0)                   // remaining time
            .bytes()

        val data = FTMSParser.parseIndoorBikeData(packet)!!

        assertEquals(312, data.instantaneousPowerWatts)
        assertEquals(280, data.averagePowerWatts)
        assertEquals(7, data.resistanceLevel)
        assertEquals(95.0, data.instantaneousCadenceRpm!!, 0.001)
        assertEquals(12345, data.totalDistanceMetres)
        assertEquals(142, data.heartRateBpm)
        assertEquals(8.5, data.metabolicEquivalent!!, 0.001)
        assertEquals(1830, data.elapsedTimeSeconds)
    }

    @Test
    fun `keeps the fields it decoded when a packet is truncated`() {
        // A trainer that promises power in the flags but sends only one byte of it.
        val packet = Packet().u16(0x0044).u16(2500).u16(180).u8(250).bytes()

        val data = FTMSParser.parseIndoorBikeData(packet)!!

        assertEquals(90.0, data.instantaneousCadenceRpm!!, 0.001)
        assertNull(data.instantaneousPowerWatts)
    }

    @Test
    fun `returns null when the packet cannot even hold the flags`() {
        assertNull(FTMSParser.parseIndoorBikeData(byteArrayOf(0x01)))
        assertNull(FTMSParser.parseIndoorBikeData(ByteArray(0)))
    }

    @Test
    fun `maps the not-available sentinel to no reading`() {
        val packet = Packet().u16(0x0101).u16(0xFFFF).u16(0xFFFF).u8(0xFF).bytes()

        val data = FTMSParser.parseIndoorBikeData(packet)!!

        assertNull(data.totalEnergyKcal)
        assertNull(data.energyPerHourKcal)
        assertNull(data.energyPerMinuteKcal)
    }
}
